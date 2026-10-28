# 0003. Outbox over dual writes

Status: accepted (Phase 4)

## Context

Every committed entry must reach the Kafka topic `ledger.entries` as it was saved, and an entry that rolled back must never reach it. The database is the source of truth (CLAUDE.md). PostgreSQL and Kafka cannot commit together. A request that writes to both always has a moment when only one of them holds the change. A crash, timeout or rollback in that moment loses an event or announces money that never moved.

## Decision

- The posting transaction writes an outbox row. `OutboxWriter.write` runs with `Propagation.MANDATORY` and inserts `(entry_id, type, payload)` with `cast(:payload as jsonb)` (`V5__outbox.sql`). The payload is the same JSON string saved for idempotent replay (`PostingServiceImpl.announceAndSave`).
- `OutboxRelay` starts 200 ms after its previous run ends (`@Scheduled(fixedDelay = 200)`). Each run is its own transaction with a 10 s timeout:
  1. lock up to 100 unpublished rows, ordered by id, with `for update skip locked`;
  2. send all of them to `ledger.entries`, keyed by `entry_id`;
  3. wait up to 4 s for every ack;
  4. set `published_at`, then commit.

  Any failure rolls the batch back, so its rows go out again on a later run.
- The producer settings are `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms` 3000, `request.timeout.ms` 2000 and `max.block.ms` 1000 (`application.yaml`). The topic has 3 partitions and 1 replica, because the local broker is single-node.
- `tally_app` may UPDATE only `outbox.published_at`.
- Delivery is at least once, and consumers dedupe on `entry_id`. Kafka keeps order per entry, not per account ([docs/design.md](../design.md)).

## Consequences

Good:

- An event exists exactly when its entry committed.
- Posting does not need Kafka. In `KafkaOutageIT` (Phase 6 Verify, 2026-10-27), Kafka was cut for 60 s. All 496 transfers posted during the cut were answered 201. 497 rows were unpublished at the restore, and the outbox was empty 2,232 ms later, with 0 repeats.
- Relays can run side by side. `OutboxRelayIT` passed 10 of 10 runs with 0 duplicates, two-relay test included (Phase 4, 2026-10-19). Two mutation runs removed `skip locked`, and the two-relay test then saw 29 and 32 duplicates.

Costs:

- Duplicates are by design. In one `DbConnectionDropIT` run (2026-10-27), Kafka acknowledged a batch, the update of `published_at` then failed on the cut link, and 17 entries reached Kafka twice ([docs/failure-table.md](../failure-table.md)).
- Kafka can fall far behind. In Phase 8 the relay published 294 to 480 rows a second against 685 to 1,857 postings a second, and it shares the pool of 10 connections with the postings. Kafka ended the final scenario A run (2026-10-28) 341,525 rows behind. No money was lost; Kafka was late ([PROGRESS.md](../../PROGRESS.md) Phase 8).
- Open items in PROGRESS.md Ideas:
  - a row that Kafka refuses permanently blocks every later row;
  - published rows are never deleted;
  - during an outage the relay retries with no backoff;
  - the relay's batch size and interval cap its rate.

## Alternatives

- Send to Kafka after the commit. A crash between the commit and the send loses the event, and nothing is left to retry from.
- Send before the commit. If the commit then fails, Kafka has announced money that never moved.
- Kafka transactions. They cannot include the PostgreSQL commit, so the gap remains.
- Change data capture from the WAL. It is a new component to run and a dependency the plan does not list.
