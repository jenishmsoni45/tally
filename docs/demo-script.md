# Demo script

A two-minute demo of tally. The times are slots in the script's plan, not measurements.

## Before you start

- JDK 25 is installed (the build does not download one), Docker is running, and `curl`, `jq` and `openssl` are installed.
- Terminal 1 (the app), at the repo root:

  ```sh
  export TALLY_ADMIN_API_KEY=$(openssl rand -hex 32)
  echo "$TALLY_ADMIN_API_KEY"
  ./gradlew bootRun
  ```

  Wait for `Started TallyApplication` (33.8 s in a fresh clone on 2026-10-28, Apple M4, images already pulled). The key is a throwaway value for this local run. Copy it for terminal 2 before you start recording.
- Terminal 2 (the requests):

  ```sh
  export TALLY_ADMIN_API_KEY=<the value printed in terminal 1>
  TALLY=localhost:8080
  RUN=$(date +%s)
  ```

  `RUN` makes the account codes and keys new on every run of the demo. The local database keeps its data between runs, and an account code can be used only once.
- Terminal 3 (the tests), at the repo root. Run the command from the last step once before the demo, so its test classes are already compiled.
- A browser with Grafana open at http://localhost:3000, in Explore, with the Tempo data source selected.

## 0:00 to 0:10: the pitch

"tally is a double-entry ledger service. Every money movement is a balanced, append-only journal entry. A retried request moves money once. Every entry reaches Kafka through an outbox, and a recount proves that the stored balances match the lines."

## 0:10 to 0:25: three accounts

In terminal 2:

```sh
FUNDING=$(curl -s -X POST "$TALLY/accounts" -H 'Content-Type: application/json' \
  -d "{\"code\":\"funding-$RUN\",\"currency\":\"CAD\",\"allowNegative\":true}" | jq -r .id)
ALICE=$(curl -s -X POST "$TALLY/accounts" -H 'Content-Type: application/json' \
  -d "{\"code\":\"alice-$RUN\",\"currency\":\"CAD\",\"allowNegative\":false}" | jq -r .id)
BOB=$(curl -s -X POST "$TALLY/accounts" -H 'Content-Type: application/json' \
  -d "{\"code\":\"bob-$RUN\",\"currency\":\"CAD\",\"allowNegative\":false}" | jq -r .id)
echo "funding=$FUNDING alice=$ALICE bob=$BOB"
```

Say: "Money enters the ledger from the funding account, which may go negative. The customer accounts may not."

## 0:25 to 0:45: a transfer, then the same request again

```sh
KEY="demo-$RUN-deposit"
BODY="{\"fromAccountId\":\"$FUNDING\",\"toAccountId\":\"$ALICE\",\"amount\":5000,\"currency\":\"CAD\",\"description\":\"deposit\"}"
FIRST=$(curl -s -X POST "$TALLY/transfers" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY")
echo "$FIRST" | jq
ENTRY=$(echo "$FIRST" | jq -r .id)
```

Point at these parts of the response: kind `transfer`, and two lines, -5000 on the funding account and 5000 on Alice's. Amounts are integer cents.

Send the same request again with the same key:

```sh
curl -s -X POST "$TALLY/transfers" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY" | jq -r .id
echo "$ENTRY"
curl -s "$TALLY/accounts/$ALICE/entries" | jq '.items | length'
```

Say: "Same entry id, and Alice has one line. The retry replayed the saved response and moved nothing."

## 0:45 to 0:55: the same key with another amount is a 409

```sh
curl -s -w '\nHTTP %{http_code}\n' -X POST "$TALLY/transfers" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"fromAccountId\":\"$FUNDING\",\"toAccountId\":\"$ALICE\",\"amount\":9000,\"currency\":\"CAD\",\"description\":\"deposit\"}"
```

Expect `HTTP 409` with a problem body whose detail is "Idempotency-Key was already used for a different request".

## 0:55 to 1:05: an overdraft is a 422

```sh
curl -s -w '\nHTTP %{http_code}\n' -X POST "$TALLY/transfers" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$RUN-overdraft" \
  -d "{\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$BOB\",\"amount\":8000,\"currency\":\"CAD\"}"
```

Expect `HTTP 422` with the detail "Account <Alice's id> has 5000 and the entry changes it by -8000". Say: "Nothing was saved, not even the key, so the same request can be retried once Alice has the funds."

## 1:05 to 1:15: the balance and the recount

```sh
curl -s "$TALLY/accounts/$ALICE/balance" | jq
curl -s -X POST "$TALLY/admin/reconcile" -H "Authorization: Bearer $TALLY_ADMIN_API_KEY" | jq
```

Expect Alice's balance to be 5000 CAD, and the recount to show `driftedAccounts` 0 and an empty `drift` list. Say: "Every stored balance equals the sum of its lines. The recount only reports; it never edits a balance. Without the Bearer key, this endpoint answers 401."

## 1:15 to 1:40: one entry in Grafana

Print the query for this entry:

```sh
echo "{ span.entry_id = \"$ENTRY\" }"
```

In Grafana, in Explore with Tempo, paste the query as TraceQL and run it. Expect:

- the request trace, rooted at `http post /transfers`;
- the relay's trace, rooted at `outbox publish`, with one `ledger.entries send` span for each entry in its batch.

This entry was also replayed, and `PostingTelemetry` tags the replay's request span with the same `entry_id`, so a second trace rooted at `http post /transfers` appears too: three traces in all. The 409 request carries no `entry_id`. Tempo indexes new traces with a delay: in a run on 2026-10-28 all three were found about 30 s after the posting. If fewer show up, run the query again.

In terminal 1, the JSON log lines "Posted entry", "Replayed entry" and "Published entry" carry the same `entry_id`, each with its own trace's `traceId`. The posting lines also carry the `idempotency_key`.

## 1:40 to 2:00: the failure tests

In terminal 3:

```sh
./gradlew test --tests 'RetryStormIT' --tests 'DbConnectionDropIT' --rerun
```

Both tests run the app against PostgreSQL and Kafka behind Toxiproxy, in Testcontainers that are separate from the `bootRun` stack ([failure-table.md](failure-table.md)).

- `RetryStormIT` sends 50 identical requests with one key at once. When it passes, it prints `RetryStormIT: requests=50 posted=1 replayed=49`.
- `DbConnectionDropIT` cuts the database link for 3 s during 200 concurrent transfers, and every client retries with the same key until it gets a 201. Every failed attempt must be a 503 problem. It prints `DbConnectionDropIT seed=20261027 clients=200 postedWhenCut=... failedBeforeCut=... failedAttempts=... totalMs=...`.
- After each test, the base class `FailureTest` checks four things: money per currency is unchanged, every balance equals its lines, each key has at most one entry, and the Kafka consumer saw every entry. It prints `<test class>.<test method>: keys=... entries=... failedAttempts=... duplicatesIgnored=...`.

Run time, 2026-10-28, Apple M4, while `bootRun` was running in the same folder: 12.4 s once the test classes were compiled, 26.5 s the first time in a fresh clone. If it does not fit in the slot, start the command in terminal 3 before the pitch and show the result here.

Gradle shows only `BUILD SUCCESSFUL` on the console; the printed lines go to the test results. Show them with:

```sh
grep -h -E 'requests=|seed=|keys=' build/test-results/test/TEST-*RetryStormIT.xml \
  build/test-results/test/TEST-*DbConnectionDropIT.xml
```

or open `build/reports/tests/test/index.html` and each class's "Standard output" tab.
