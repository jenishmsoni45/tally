# 0005. Append-only entries with reversals

Status: accepted (Phase 2A, reversals in Phase 3)

## Context

A ledger is a book of record. What was posted stays as posted, so every balance can be explained by its lines, and a mistake and its correction are both visible. Editing or deleting a line would change the past, break the match between balance and history, and leave Kafka consumers holding an entry the ledger no longer has. Mistakes still happen, so an entry's effect must be undoable.

## Decision

- Inserts only, for the application. Flyway runs as the owner. The app connects as `tally_app`, which has SELECT and INSERT on `accounts`, `journal_entries` and `journal_lines` and no other privilege on them (`V3__app_role.sql`). Of the ledger tables, `balances` is the only one the app may UPDATE, and only under the posting's row lock. Elsewhere the app may update `idempotency_keys`, which it does only to save a key's response (`V4`), and an outbox row's `published_at` (`V5`).
- Deferred constraint triggers (`V2__journal.sql`) refuse at commit an entry that does not sum to zero in each currency, and an entry with no lines. A composite foreign key ties a line's currency to its account's. The service checks balance first, and the triggers are the backstop.
- `POST /entries/{id}/reverse` posts the mirror image of the original's lines as a new entry. The new entry has kind `reversal` and its `reverses_id` points at the original. It goes through the full posting recipe, overdraft check included, so it can be refused with a 422 and retried later.
- An entry can be reversed only once. A unique partial index on `reverses_id` enforces this (`V4__idempotency.sql`), and the service checks right after the locks are held, so a second reversal is a 409 even when it would also overdraw. A reversal can itself be reversed once (`PostingService` Javadoc).
- `ReconciliationJob` (`POST /admin/reconcile`) compares each stored balance with the sum of its lines. It appends any drift to `reconciliation_drift` (insert-only for `tally_app`) and never edits a balance.

## Consequences

Good:

- Full history. An account's lines explain its balance, and an asOf balance is the sum of the lines of entries created by then.
- The app cannot rewrite history. `JournalSchemaIT.theApplicationRoleCannotChangeOrDeleteJournalRows` runs UPDATE and DELETE on both journal tables as `tally_app` and expects `insufficient_privilege`. `JournalSchemaIT` also covers the commit-time checks and the one-reversal index.
- `ReversalIT`'s eight-at-once test passed 20 of 20 runs, each with one 201 and seven 409s and never a 422 (Phase 3, 2026-10-18). A mutation moved the reversed check after the overdraft check, and two `ReversalIT` tests failed ([PROGRESS.md](../../PROGRESS.md) Phase 3).
- Drift is reported, never patched. `ReconciliationIT` passed 20 of 20 runs while transfers committed during the recounts (Phase 5). The crash test and every failure test ended with no drift ([docs/failure-table.md](../failure-table.md)).

Costs:

- Undoing a mistake takes a second entry, and posting the corrected version takes a third. The reversal is dated when it is posted, because the endpoint takes no body.
- Storage only grows: journal, outbox and key rows are never deleted (PROGRESS.md Ideas).
- The stored balance is a second copy of the truth, kept in step by the posting and checked by the recount. asOf filters on `created_at`, the transaction start, so it can miss a posting that is still committing (PROGRESS.md Ideas).
- The guarantee is against the app role, not the owner. `ReconciliationIT` plants drift as the owner.
- Nothing in the schema ties `kind` and `reverses_id` together (PROGRESS.md Ideas).

## Alternatives

- UPDATE or DELETE lines. History changes, the reason for the change is lost, and Kafka has already announced the old entry.
- Soft-delete flags. Every query must filter on them, a balance then depends on a flag that can flip, and the app would need UPDATE on the journal.
- No stored balance. There would be nothing to reconcile, but the overdraft check would have to sum lines inside the posting, and the posting would still need a row per account to lock.
