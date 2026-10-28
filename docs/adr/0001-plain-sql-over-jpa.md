# 0001. Plain SQL over JPA

Status: accepted (Phase 1)

## Context

tally's money stays correct because of a handful of SQL statements. A posting locks its balance rows in one fixed order and claims an idempotency key without racing another request. It then writes the entry, lines, balances, outbox row and saved response in one transaction. The relay takes only rows no other relay holds. The recount reads balances and lines from one snapshot. Reviewers must be able to see these statements. An ORM decides when SQL runs (flush order, dirty checking, lazy loads), and its unit of work assumes it may update what it loaded. An insert-only journal needs the opposite.

## Decision

All data access uses Spring's `JdbcClient` with named parameters. The calls live in a repository or component inside a feature package: `AccountRepository`, `BalanceRepository`, `JournalRepository`, `IdempotencyStore`, `OutboxWriter`, `OutboxRelay`, `OutboxMetrics` and `ReconciliationRepository`. Every value is bound as a parameter, lists and VALUES rows included. The posting path relies on SQL that is written out in full:

- `... where account_id in (:accountIds) order by account_id for update` locks all of a posting's balance rows in one statement (`BalanceRepository.findBalancesForUpdate`, [0002](0002-sorted-row-locks-over-serializable.md)).
- `update balances ... from (values :rows)` writes every new balance in one statement (`updateBalances`).
- `insert ... on conflict (key) do nothing` claims a key (`IdempotencyStore.claim`, [0006](0006-idempotency-key-semantics.md)).
- `for update skip locked` lets several relays share the outbox (`OutboxRelay.lockUnpublished`).
- `cast(:payload as jsonb)` stores the entry's JSON, and `returning` hands back line ids and `created_at` without a second read.
- The recount is a single CTE that inserts the drifted accounts and returns them (`ReconciliationRepository.recordDrift`).

Rows map by hand to immutable records. The schema comes from Flyway migrations `V1` to `V6`.

## Consequences

Good:

- Each statement a posting sends can be read and counted. In Phase 8 a transfer sent 12 statements and the commit, and the one change (`f47b380`) brought that down to 9. The change turned the per-account read, lock and update into one statement each. Scenario A gained about 16% throughput: the median of four alternating runs each was 1,333 req/s before and 1,543 after (2026-10-27, [PROGRESS.md](../../PROGRESS.md) Phase 8).
- Money rules sit in the schema, where the app cannot get around them. `JournalSchemaIT.theApplicationRoleCannotChangeOrDeleteJournalRows` runs UPDATE and DELETE on both journal tables as `tally_app` and expects `insufficient_privilege` (grants in `V3__app_role.sql`).
- The lock order is a property of one statement. It does not depend on how an ORM orders its loads.

Costs:

- Row mappers and SQL strings are maintained by hand. A renamed column fails a test, not the compiler.
- Nothing is generated from entities. Every table, index and grant is a migration, and each new table needs its `tally_app` grants written out (PROGRESS.md Ideas).
- Every query needs human review. In Phase 8 an entry without lines turned the account read into `in ()`, a SQL grammar error. The service now refuses such an entry first (`f9ea6f4`).

## Alternatives

- JPA with Hibernate. It flushes when it chooses and updates entities it sees as changed. Ordered locking, `on conflict` and `skip locked` would be native queries anyway, so the posting path would be SQL inside an ORM. Never tried here.
- Spring Data JDBC. Saving an aggregate that is not new deletes and re-inserts its child rows, which `tally_app` may not do to `journal_lines`.
- jOOQ. It keeps SQL explicit and type-safe, but it is a dependency PLAN.md does not list, and its code generation adds a build step.
