# 0002. Sorted row locks over SERIALIZABLE

Status: accepted (Phase 2B)

## Context

A posting reads the balances it touches, checks for overdrafts, and writes the new balances. Two postings on one account must never both read the old balance, or an update is lost and two overdraft checks can pass that together overdraw the account. A transfer touches two accounts and an entry can have up to 100 lines, so two postings can each hold an account the other needs. PostgreSQL offers explicit row locks (`SELECT ... FOR UPDATE`) under the default READ COMMITTED isolation. It also offers SERIALIZABLE, which aborts one of two conflicting transactions and leaves the caller to retry.

## Decision

- Transactions run at READ COMMITTED, PostgreSQL's default. No isolation level is set anywhere in `src/main`.
- `BalanceService.lockBalances` locks every touched balance row in one statement, `select ... where account_id in (:accountIds) order by account_id for update` (`BalanceRepository.findBalancesForUpdate`). PostgreSQL sorts before it locks, so every posting locks in ascending `account_id` order. This has been one statement since `f47b380` (Phase 8). Before that, rows were locked one per statement, in the same order.
- The service orders accounts by `UUID::toString`, which matches PostgreSQL's uuid order, so a 422 for funds names the first short account in that order (`PostingServiceImpl.netChangePerAccount`). It used to use `UUID.compareTo`, which is signed and differs from PostgreSQL's order. With that order, a later `order by account_id for update` could have deadlocked against a posting. `3f4dbcb` fixed it in Phase 2B.
- Locks are held until commit. Lines are inserted only after the locks are taken, so line ids on one account follow commit order ([docs/design.md](../design.md)).
- A reversal locks the same accounts as its original and checks for an existing reversal right after the locks are held (Phase 3). Any competing reversal has committed by then, and READ COMMITTED's next statement sees it.

## Consequences

Good:

- No deadlocks, and no retry loop for serialization failures. `ConcurrencyIT` runs 10 accounts with 50 threads x 200 transfers, seed 20261017. It passed 20 of 20 runs in Phase 2B (2026-10-18) and again after `f47b380`. The branch `experiment/unsorted-locks` locked in request order and passed 0 of 5 runs. In its last run 5093 of 10000 transfers failed with unexpected errors, among them `ERROR: deadlock detected` ([PROGRESS.md](../../PROGRESS.md) Phase 2B).
- Under READ COMMITTED, a `FOR UPDATE` that waited returns the newest committed balance, so the waiter continues from the right number.

Costs:

- A hot account serializes its postings. In the final runs (2026-10-28, PROGRESS.md Phase 8), scenario B, which pays account 1 every time, reached 685 req/s against 1,857 req/s for scenario A. During B, about 7.8 of the 8.0 active database sessions were waiting on a lock.
- Every code path that locks balances must use this order. No test pins it (PROGRESS.md Ideas).
- A lock wait counts against the 5 s transaction timeout. If a lock or serialization failure ever surfaced, `ApiExceptionHandler` maps Spring's transient data errors to a 503, and the client retries with the same key. No test forces that path.

## Alternatives

- SERIALIZABLE with retries. Writers of one row still queue on its lock, and when the first one commits, PostgreSQL aborts the waiter, which must then rerun the whole posting. On a hot account most postings would run more than once. This is reasoning only: SERIALIZABLE was never measured here.
- Optimistic version columns. Under contention they pay the same retry cost, and the overdraft check reads a balance that may be stale.
- Advisory locks. They need the same fixed order, and they add a second locking scheme that the rows do not show.
- A single writer per account. It removes lock waits, but a two-account posting would need two writers to agree.
- Splitting a hot account's balance over several rows. That raises the ceiling but changes the balance model (PROGRESS.md Ideas, not built).
