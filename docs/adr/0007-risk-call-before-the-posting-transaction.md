# 0007. Risk call before the posting transaction

Status: accepted (Phase 10)

## Context

In Phase 10 a transfer can ask the risk engine, a separate service, whether it may go ahead ([docs/risk-decision-call.md](../risk-decision-call.md)). The risk engine answers allow, hold or block, or gives no decision: it can be slow, down, or send a reply tally cannot use. tally waits at most `tally.risk.timeout` (100 ms). A posting is one transaction on one of the pool's 10 connections. It claims the idempotency key, locks the touched balance rows and holds them until commit, so postings on one account queue on its lock (ADR 0002). Where the call sits decides what tally holds while it waits, and whether a refusal can still stop the money from moving.

## Decision

- The call is made before the posting transaction opens, by a non-transactional step in the posting package that runs ahead of the `@Transactional` `PostingService.postTransfer`. The transaction boundary stays on the posting service, and the call is made from a different bean, never from inside the transactional method.
- `POST /transfers` with the check on runs these steps:
  1. Bean Validation (400).
  2. A replay check: whether the `Idempotency-Key` has a saved response, read outside any transaction. If it has, the existing transactional path replays it or answers 409, and the risk engine is not asked.
  3. The call, with no transaction open and no pooled connection held.
  4. The outcome. Allow posts the transfer with `riskCheck` `decided` and the `decisionId`. Hold or block answers 403 with the `decisionId`. The risk engine's 409 becomes tally's key-conflict 409. No decision uses the backup answer.
- The backup answer is the `onTimeout` of the risk engine's latest valid reply, kept in memory, or `tally.risk.on-timeout-default` (`hold`) before the first reply. Every reply, a replayed decision included, carries the current rule set's `onTimeout`. Backup allow posts with `riskCheck` `fallback` and `decisionId` null. Backup hold or block answers 503 `risk-unavailable`.
- The posting transaction is unchanged apart from writing `decision_id` and `risk_check` on the entry (`V7`).
- Only `POST /transfers` asks; entries and reversals get `riskCheck` `none`. The check is off by default (`tally.risk.enabled`).

## Consequences

Good:

- While tally waits on the risk engine it holds no transaction, row lock or pooled connection, so a slow risk engine delays only the transfers that ask it. `RiskDecisionIT` covers this: while one transfer waits on a slow fake risk engine, another between the same two accounts commits.
- A held or blocked transfer never moves money, so there is nothing to undo. Each checked entry records the decision that let it through, and `riskCheck` `fallback` marks money that moved unchecked.

Costs:

- A transfer the risk engine allowed can still be refused by tally (unknown account 404, wrong currency or insufficient funds 422). The risk engine then has a decision with no entry, and tally does not tell it what happened. A retry of that key with a corrected transfer is then a 409, because the risk engine has decided the key for the first one; with the check off, tally would post it.
- During a risk engine outage every transfer still asks it and can wait up to the timeout before tally uses the backup answer. There is no circuit breaker in this phase (PLAN.md stretch list). While the backup answer is `hold`, the default, an outage stops every transfer.
- A refusal by the backup answer is a 503, not a 403, because nothing was decided. The key is not saved, so a retry with the same key asks the risk engine again.
- `ledger.posting.duration` times the whole HTTP path, so with the check on it includes the call. `ledger.risk.duration` times the call alone.
- Two first requests with one key both pass the replay check and both ask the risk engine, which gives both the same answer. The claim inside the transaction then lets one post and the other replay.
- The backup answer lives in memory per instance, so after a restart it is the configured default until the first reply. Two replies in flight while the risk engine changes its rule set can arrive in either order, so for a moment tally can keep the older rule set's backup answer, until the next reply.

## Alternatives

- Call inside the posting transaction. The transaction would hold its pooled connection and its claimed key while waiting on another service, and after the lock step the balance rows too. With a pool of 10, a slow risk engine would stall all posting, entries and reversals included, and postings on a busy account would queue behind every wait. The 5 s transaction timeout would also have to cover the call.
- Call after commit. The money has already moved and the event is on its way to Kafka. A hold or block could only be undone by a reversal entry, which is a second movement with its own overdraft check.
- Asynchronous review: accept the transfer now and decide later. It needs a pending state and a way to release or cancel a held transfer, which tally doesn't have (out of scope in [docs/risk-decision-call.md](../risk-decision-call.md), section 5).
