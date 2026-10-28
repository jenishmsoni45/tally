# 0006. Idempotency key semantics

Status: accepted (Phase 3)

## Context

A client whose request timed out cannot tell whether its transfer was saved. It must be able to resend the request and get the first answer, without the money moving twice. Copies of one request can also arrive together in a retry storm, and a client can reuse a key for a different request. All of this must hold even when the app dies between the commit and the response.

## Decision

- `POST /transfers`, `/entries` and `/entries/{id}/reverse` require an `Idempotency-Key` header. A valid key is printable ASCII without spaces, 1 to 255 characters (`PostingController`). A missing or malformed key gets a 400.
- `RequestHash` is the SHA-256, as hex, of the operation name, a newline, and the bound request serialized again. It is not a hash of the raw body. The server reads the same request the same way whatever the field order, so it hashes the same, and a field left out hashes like a null one. The operation is in the hash, so a key cannot be replayed on another endpoint. PLAN.md asked for "the canonical JSON body", and this is how that was built ([PROGRESS.md](../../PROGRESS.md) Phase 3).
- `IdempotencyStore.claim` runs inside the posting transaction (`Propagation.MANDATORY`) and executes `insert ... on conflict (key) do nothing`.
  - When the insert adds a row, the request does the work and saves its status and body in the same transaction.
  - When it adds nothing, the stored row decides: a different hash gets a 409, and the same hash replays the saved status and body.
- A concurrent request with the same key waits on the first one's uncommitted row. If the first commits, it replays the first one's response. If the first rolls back, it does the work itself.
- Only successful postings keep their key. A refused posting (422, 404, or 409 for an entry already reversed) rolls back with its key, and the client can retry later.
- Keys are global and never expire (PROGRESS.md Ideas).

## Consequences

Good:

- Retries never move money twice. `IdempotencyIT`'s twenty-threads test (20 requests with one key at once: one entry, identical 201 bodies) passed 20 of 20 runs (Phase 3, 2026-10-18). `RetryStormIT` (Phase 6 Verify, 2026-10-27) sent 50 requests with one key: all got 201 with identical bodies, timed as 1 posted and 49 replayed.
- Crashes are covered. In the Phase 6 crash test (`kill -9`, 2026-10-27 18:48 UTC), 1,756 entries had committed but k6 had only 1,755 answers of 201. The replay answered all 1,756 keys from saved responses and posted the other 3,244, which made 5,000 entries and left no drift.
- Load is covered too. After the first Phase 8 saturation run (2026-10-27 21:11 UTC), the database held 1,151,537 transfer entries for 1,151,537 transfers sent.
- A reused key is caught. When a Phase 7 mutation check removed the key-conflict check, simulation seed 1 failed at step 40.

Costs:

- A waiting duplicate holds a pooled connection until the first request ends. `RetryStormIT` sends five times the pool's 10 connections and every request is answered, but a bigger storm would queue.
- The hash depends on the request records. A record that gains a field changes the hash of keys saved before it, so their retries get a 409 (`RequestHash` Javadoc).
- A retry after a refusal can get a different answer: `IdempotencyIT.aRefusedRequestDoesNotUseUpItsKey` gets a 422 and later a 201 with the same key.
- With no expiry and no caller scope, the table grows without bound and two callers can pick the same key (PROGRESS.md Ideas).
- Only the manual crash test covers a commit whose answer was lost (PROGRESS.md Ideas).

## Alternatives

- Lock the key and answer an in-flight duplicate with a 409 at once, telling the client to retry shortly. Shopify's "Building Resilient GraphQL APIs Using Idempotency" describes this, with the lock on the client and the key together. It frees the connection, but the client has to come back.
- Hash the raw body. Then the same request with its fields in another order would get a 409.
- Save refusals too. A retried 422 would replay the 422, but a client that funded the account would need a new key.
- Expire keys after a time window. This bounds the table, but a late retry would post again (PLAN.md stretch list).
