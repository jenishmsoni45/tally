# Risk decision call

The spec for Phase 10 in PLAN.md. Before a transfer opens its posting transaction, tally asks the risk engine (project 2, `github.com/jenishmsoni45/risk-engine`) whether it may go ahead. The risk engine answers allow, hold or block from rules over rolling per-account features it builds from tally's `ledger.entries` events. The decisions below were settled in the risk engine's planning (its `docs/decisions.md`, D2 to D11).

## 1. The story

Today a transfer goes: validate, open the posting transaction, move the money, write the outbox row, commit, answer 201. The relay later announces the entry on Kafka.

With the risk check switched on, tally first asks the risk engine ("the guard") whether the transfer may go ahead, and only then opens its transaction:

1. Validate the request, as today.
2. If this Idempotency-Key already has a saved response, skip the guard and replay (or 409), as today.
3. Ask the guard, waiting at most 100 ms. No transaction is open and no database connection is held while waiting.
4. Allow: post as today, and record the decision id on the entry. Hold or block: refuse with 403. The guard doesn't answer in time: use the backup answer.
5. The entry, its 201 body and its Kafka event carry `decisionId` and `riskCheck`, so the risk engine can join each decision to the money it moved.

## 2. Decisions

| Decision | What | Why |
|---|---|---|
| Who asks | tally calls the decision API itself, rather than only a stand-in in the risk engine's tests | The real end-to-end path, documented in tally |
| Hold and block | Refuse with 403 and a problem type per outcome; the body has the `decisionId`, never the fired rules | 403 means refused by policy; 422 would say "fix your request", which a held customer can't. Fired rules stay with the risk engine so nobody can tune against them |
| Timeout | tally waits at most 100 ms; the risk engine's budget is p99 within 50 ms | Room for a Python service on a laptop while a transfer still feels instant |
| Backup answer | Each guard reply, a replayed decision included, carries the current rule set's `onTimeout`; tally remembers the latest and uses its config default, `hold`, before the first reply | The backup answer stays per rule set without a tally config change when the rules change, and a retried old key can't bring back an older rule set's; fail closed by default |
| Backup refusal | A refusal by the backup answer is a 503, not a 403 | Nothing was decided, and a retry with the same key asks the guard again |
| Join | tally stores `decisionId` and `riskCheck` on the entry and sends both in the 201 and the event | tally keeps an audit record of which decision let each transfer through, and money that moved unchecked is easy to find |
| Scope | Only `POST /transfers` asks the guard | Customer money movement is what fraud targets; entries and reversals are bookkeeping |
| Default | The check is off by default | tally behaves exactly as before without the risk engine, so every existing test, the simulation and the load runs are unchanged |
| Key reuse | The guard answers 409 for a requestId it already decided for a different transfer; tally passes it on as its own key-conflict 409 | Matches tally's own Idempotency-Key rule: one key, one request, one answer |

## 3. The contract

### 3.1 tally → risk engine

```
POST {tally.risk.url}/v1/decisions
Content-Type: application/json

{
  "requestId": "<the transfer's Idempotency-Key>",
  "fromAccountId": "<uuid>",
  "toAccountId": "<uuid>",
  "amount": 12500,
  "currency": "CAD",
  "requestedAt": "2026-11-20T15:04:05.123Z"
}
```

- `requestId` is the Idempotency-Key exactly as received. `amount` is the positive amount in cents, `currency` the 3-letter code, both as in the transfer request.
- `requestedAt` is tally's clock (`Instant.now()`) just before the call, ISO-8601 UTC. The risk engine computes features as of this time.

### 3.2 risk engine → tally

| Guard response | Meaning | What tally does |
|---|---|---|
| 200 with a valid body, `decision: "allow"` | Allowed | Posts the transfer with `riskCheck: decided` and the `decisionId` |
| 200, `decision: "hold"` | Held for review | 403 problem, type `transfer-held`, with `decisionId`; nothing posts |
| 200, `decision: "block"` | Blocked | 403 problem, type `transfer-blocked`, with `decisionId`; nothing posts |
| 409 problem | This requestId was already decided for a different transfer | The existing key-conflict 409 ("Idempotency-Key was already used for a different request") |
| No answer within the timeout, connection refused or reset, any other status, a body over 16 KiB, a body that doesn't parse, or a body with a value outside the lists below | No decision | Backup answer (3.4) |

A 200 body:

```json
{
  "decisionId": "7c1e9a52-3a0f-4b8e-9d55-0f3c2b1a6e47",
  "decision": "hold",
  "ruleSet": "v3",
  "fired": ["velocity_1m"],
  "onTimeout": "hold"
}
```

- `decisionId` is a UUID. `decision` and `onTimeout` are each one of `allow`, `hold`, `block`. A 200 that breaks any of these is no decision.
- A 200 body is at most 16 KiB. A valid one is a few hundred bytes, and the cap stops a broken guard from making tally buffer an unbounded body; a longer one is no decision.
- tally uses `decisionId`, `decision` and `onTimeout`. It does not store, log or return `ruleSet` or `fired`.
- The same `requestId` with the same `fromAccountId`, `toAccountId`, `amount` and `currency` always gets the same `decisionId`, `decision`, `ruleSet` and `fired` back, whenever it is asked. `requestedAt` is not part of that match.
- `onTimeout` is always the current rule set's, in a replayed decision too, so a retried old key can't bring back an older rule set's backup answer (3.4).

### 3.3 tally → customer

Unchanged: every existing status and body, including 400 for validation, which runs before the guard is asked.

New:

```
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{ "type": "/problems/transfer-held", "title": "Transfer held for review", "status": 403,
  "detail": "The risk check held this transfer for review.", "decisionId": "7c1e9a52-…" }
```

```
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{ "type": "/problems/transfer-blocked", "title": "Transfer blocked", "status": 403,
  "detail": "The risk check blocked this transfer.", "decisionId": "7c1e9a52-…" }
```

```
HTTP/1.1 503 Service Unavailable
Content-Type: application/problem+json

{ "type": "/problems/risk-unavailable", "title": "Risk check unavailable", "status": 503,
  "detail": "The risk check did not answer in time; try again. A transfer retried with the same Idempotency-Key moves money at most once." }
```

- RFC 9457 allows a relative `type`, and Phase 10 kept the relative URIs above. The existing problems keep `about:blank`. The risk engine only needs the three types to be distinct and stable.
- A refused transfer (403 or 503) saves no idempotency key, like every other refusal. A retry with the same key goes through the guard again: a held key is held again, and a key refused by the backup answer gets a real decision once the guard is back.

### 3.4 The backup answer

- tally keeps the `onTimeout` of the latest valid 200 in memory. Before the first one, and after a restart, it uses `tally.risk.on-timeout-default` (default `hold`).
- Every 200 carries the current rule set's `onTimeout` (3.2), so the latest reply names the backup answer in force. Two replies in flight while the rule set changes can arrive in either order, so for a moment tally can keep the older rule set's value; the next reply puts it right.
- Backup `allow`: post the transfer with `riskCheck: fallback` and `decisionId: null`.
- Backup `hold` or `block`: 503 `risk-unavailable` (3.3). Nothing posts.

### 3.5 The entry and the event

Two new fields on every entry, in the 201 body, in responses saved from Phase 10 on, and in the Kafka event value:

```json
{
  "id": "…", "kind": "transfer", "reversesId": null, "description": "rent",
  "effectiveAt": "2026-11-20T15:04:05.123456Z", "createdAt": "2026-11-20T15:04:05.123456Z",
  "decisionId": "7c1e9a52-3a0f-4b8e-9d55-0f3c2b1a6e47",
  "riskCheck": "decided",
  "lines": [ … ]
}
```

| `riskCheck` | When | `decisionId` |
|---|---|---|
| `decided` | The guard answered allow | the guard's id |
| `fallback` | The guard didn't answer and the backup answer was allow | null |
| `none` | The check is off, or the entry isn't a transfer (entries, reversals) | null |

- Both fields are always present (null is sent, like `reversesId`).
- A reversal of a checked transfer is `none`: reversals never ask the guard.
- Responses saved before Phase 10, and outbox rows written before it, don't have the fields. A replay of a response saved before Phase 10 shows them as `decisionId: null` and `riskCheck: none`, which is what V7's column default gives that entry's row, so the replay and the stored entry agree. The risk engine reads a missing field in an event as `decisionId: null, riskCheck: none`.
- Everything else about the event is unchanged: same topic, key, partitions, at-least-once delivery and line-id ordering (docs/design.md, "Event ordering and delivery").

## 4. Behaviour in detail

### 4.1 Order of steps for `POST /transfers` with the check on

1. Bean Validation, as today (400).
2. Replay check, outside any transaction: a read of the saved idempotency response for this key. If there is one, call the existing transactional `postTransfer`, which replays it or answers 409, exactly as today. The guard is not asked.
3. Ask the guard (no transaction open, no pooled connection held).
4. Act on the answer (3.2, 3.4). For allow or backup allow, call the transactional posting path, which now also writes `decision_id` and `risk_check` on the entry.

The transaction boundary stays on the posting service, so the guard call happens in a different bean: a non-transactional caller (for example a small transfer orchestrator in the posting package, or the controller) runs steps 2 to 4. A call from inside the `@Transactional` method would hold the transaction open while waiting, which is what this design avoids.

### 4.2 Races and edge cases

- Two first requests with one key at the same moment: both pass step 2 and both ask the guard, which gives both the same answer. Both then reach the idempotency claim; one posts and the other replays, as today.
- A key saved between step 2 and step 4: the claim finds it and replays. The guard's second answer for that key is the same decision.
- The guard is asked before tally's own ledger checks (unknown account 404, wrong currency or insufficient funds 422), so it also sees transfers tally then refuses. tally does not tell the guard about that outcome.
- A transfer the guard allowed can still be refused by tally (for example insufficient funds). It then has a decision but no entry. That is expected.

### 4.3 The timeout

- The whole guard call (connect, send, wait, read the body) ends within `tally.risk.timeout` plus a small margin, whatever the guard does: accept the connection and never answer, answer slowly, or send a body slowly. The tests measure this.
- Connections to the guard are reused (keep-alive), so a normal call doesn't pay for a new TCP connection. tally closes a connection after 4 s idle, and the guard keeps an idle connection open for at least 5 s, so tally never sends a request on a connection the guard is closing. The JDK client doesn't retry a POST, so such a request would be no decision.
- At most 100 calls are in flight at once: Little's law with Phase 8's peak, 1,857 transfers a second, and the guard's 50 ms p99 budget gives about 93. A transfer that finds 100 calls in flight doesn't wait for a slot: it is no decision at once, outcome `unavailable`, and gets the backup answer.
- One way to get a hard deadline: build the client on the JDK `HttpClient` with a connect timeout and a per-request timeout that together fit within the budget. Phase 10 picks the mechanism; only the measured bound counts.

### 4.4 Configuration

```yaml
tally:
  risk:
    # Off by default: tally behaves exactly as before unless this is switched on.
    enabled: ${TALLY_RISK_ENABLED:false}
    # The risk engine's decision API. 8000 is a placeholder until the risk engine fixes its port;
    # it must not be one of tally's ports (8080, 5432, 9092, 9094, 3000, 4317, 4318).
    url: ${TALLY_RISK_URL:http://localhost:8000}
    # The longest tally waits for a decision before using the backup answer.
    timeout: 100ms
    # The backup answer until the guard's first reply names one.
    on-timeout-default: hold
```

At startup, with `enabled: true`: `url` must be an absolute http(s) URL, `timeout` must be at least 1 ms, and `on-timeout-default` must be `allow`, `hold` or `block`, or tally refuses to start.

### 4.5 Observability

- Timer `ledger.risk.duration` around the guard call, tagged `outcome` = `allow`, `hold`, `block`, `conflict`, `timeout`, `unavailable` (connection refused or reset, a status other than 200 and 409, or 100 calls already in flight) or `invalid` (a body that breaks 3.2, one over 16 KiB included), with histogram buckets for p95 and p99 like the other timers.
- The call goes through a client built from Spring Boot's auto-configured builder, so it gets a client span and sends the W3C `traceparent` header; the risk engine can continue the same trace.
- `decisionId` goes into the log context next to `entry_id` and the idempotency key on the "Posted entry" line. A backup answer logs one WARN line with the key, the outcome tag and the backup answer used. No request or response bodies are logged.
- `ledger.posting.duration` keeps timing the whole HTTP path, so with the check on it includes the guard's time.
- One dashboard panel: risk calls per second by outcome, and the p99 of `ledger.risk.duration`.

### 4.6 Letting the risk engine's containers read `ledger.entries`

The Kafka service advertises only `localhost:9092`, which a container in another Compose project can't use: inside that container `localhost` is the container itself. `compose.yaml` adds a second listener that containers can reach:

```yaml
KAFKA_LISTENERS: HOST://:9092,CONTROLLER://:9093,DOCKER://:9094
KAFKA_ADVERTISED_LISTENERS: HOST://localhost:9092,DOCKER://host.docker.internal:9094
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: HOST:PLAINTEXT,CONTROLLER:PLAINTEXT,DOCKER:PLAINTEXT
ports:
  - "127.0.0.1:9092:9092"
  - "127.0.0.1:9094:9094"
```

This relies on Docker Desktop forwarding `host.docker.internal` to the Mac, which Phase 10's Verify checks with a throwaway consumer container. If that check fails, the fallback is to have the risk engine's Compose project join this project's network as an external network, with a listener advertised under the Kafka service's name; that is the owner's decision.

## 5. Out of scope for Phase 10

- The risk engine itself: rules, features, the decision log.
- A review or release flow for held transfers (tally still has no pending state).
- Asking the guard for entries or reversals.
- Telling the guard what happened after it decided (posted, or refused with 404 or 422).
- A circuit breaker that stops calling an unreachable guard for a while; during an outage every transfer waits out the timeout. (The plan's stretch list already has a circuit breaker for the posting endpoints.)
- `Retry-After` on the 503, and authentication between tally and the risk engine (both run only on this machine).

## 6. What the risk engine does with it

For context; built in the risk engine's own phases.

- Serves `POST /v1/decisions` as in 3.1 and 3.2, including the 409 and the current rule set's `onTimeout` in every 200, a replayed decision included.
- Keeps an idle connection from tally open for at least 5 s (4.3).
- Its ledger stub mimics this behaviour and the two event fields, so its CI can test without tally.
- After Phase 10, captures a fixture from a real tally event (with the new fields) and tests that its stub's events parse the same way.
- Its consumer reads `ledger.entries` through the container listener (4.6) when it runs against tally.
- Uses `decisionId` from the event to join decisions to entries, and `riskCheck: fallback` to count money that moved unchecked.
