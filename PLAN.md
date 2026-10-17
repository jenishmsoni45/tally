# tally build plan

A double-entry ledger service: every money movement is a balanced, append-only journal entry. Retried requests move money once, every movement is announced on Kafka exactly as saved, a recount job catches drift, and automated tests prove it all stays correct when the database or Kafka fails.

Work one phase at a time. Each phase lists its tasks, where parallel subagents help, its Verify step and its Done-when. Each phase ends with the "Phase end" routine in CLAUDE.md: Verify, code review, security review, scope review, local commits, then (with my permission) a push and one pull request into `main`. I review and merge the PR; then I say "next".

Alignment: the design follows what Wealthsimple (Java/Kotlin, SQL, Kafka, append-only ledgers, reconciliation, audit trails) and Shopify (idempotency keys, short timeouts, structured logs, reconciliation, load testing near saturation) describe in their public engineering writing. Phases 4, 5, 8 and 9 carry the items added for that.

---

## Phase 0 · Tools and scaffolding

Tasks
1. Check the tools: `java -version` (need 25), `docker --version` and that Docker is running, `k6 version`, `git --version`. If one is missing, stop and tell me exactly what to install (suggest Eclipse Temurin 25 for Java); don't install anything yourself.
2. Generate the project into this folder from the start.spring.io API: Gradle Kotlin DSL, Java, Spring Boot 4.1.x (latest patch), Java 25, group `dev.jenishsoni`, artifact and name `tally`, package `dev.jenishsoni.tally`, jar. Dependencies: Spring Web, JDBC API, PostgreSQL Driver, Flyway Migration, Spring for Apache Kafka, Validation, Spring Boot Actuator, Docker Compose Support, Testcontainers, OpenTelemetry. Fetch the start.spring.io metadata first to get the exact dependency ids; don't guess them. Unzip into this folder without overwriting CLAUDE.md, PLAN.md, PROGRESS.md or .claude/.
3. Create the feature packages from CLAUDE.md.
4. `compose.yaml`: PostgreSQL, single-node Kafka from the official `apache/kafka` image (no ZooKeeper), and `grafana/otel-lgtm`. Look up current image tags.
5. `application.yaml`: virtual threads on, Flyway on, JDBC time zone UTC, actuator health exposed. Set the Hikari pool size and connection timeout explicitly, with a comment on why each value was chosen. Database password and similar values read from environment variables, with throwaway local defaults.
6. `.github/workflows/ci.yml`: `./gradlew build` on push and pull_request with Temurin Java 25 (look up current action versions), Gradle caching, `permissions: contents: read`, and cancel-in-progress concurrency. Testcontainers needs Docker, which GitHub's ubuntu runners have. This runs on GitHub for real once the first PR is opened.
7. `docs/design.md` with only these headings: Problem, Goals, Non-goals, Open questions.
8. `.gitignore` covering build/, .gradle/, .idea/, *.iml, .env, *.log, .DS_Store, out/; plus a minimal `.editorconfig` (UTF-8, LF, final newline, 4-space Java indent). Then `git init -b main`.
9. Keep the private files out of git with `.gitignore`: `.local/` (scratch notes and rough drafts) and `.claude/settings.local.json`. `CLAUDE.md`, `PLAN.md`, `PROGRESS.md`, `.claude/agents/` and `.claude/settings.json` are committed once they are finished, not drafts. Connect `origin` to https://github.com/jenishmsoni45/tally.git (it is private and empty; checked with `gh repo view`). Make one initial commit on `main` containing only `.gitignore`, `.editorconfig` and nothing else, and work on branch `phase-0-scaffolding`. Commit the rest in small logical commits (for example: project skeleton, compose and config, CI workflow, docs).

Parallel: after step 2, one builder writes compose.yaml and application.yaml, another writes ci.yml, design.md, .gitignore and .editorconfig. The main session does steps 1, 2, 3 and 9.

Verify: `./gradlew build`, then `./gradlew bootRun` and `curl -s localhost:8080/actuator/health`. Paste both outputs. Stop bootRun afterwards. Also paste `git ls-files | grep -E '^\.local/|^\.claude/settings\.local\.json|^\.env'`, which must print nothing.

Done when: health says UP, the commits exist on `phase-0-scaffolding`, and (after my permission) `main` and the branch are pushed and the PR is open with CI passing.

First push exception: pushing the initial `main` commit is the only time anything goes straight to `main`. I approve it explicitly in Phase 0.

---

## Phase 1 · Money and accounts

Tasks
1. `common/Money.java`: `record Money(long minor, String currency)` with `plus`, `negate`, `isZero`. Use `Math.addExact` and `Math.negateExact`. Reject mismatched currencies and any currency that isn't 3 uppercase letters.
2. `db/migration/V1__accounts.sql`: `accounts(id uuid pk, code text unique not null, currency char(3) not null, allow_negative boolean not null default false, created_at timestamptz not null default now())`; `balances(account_id uuid pk references accounts, balance bigint not null default 0, updated_at timestamptz not null default now())`.
3. `account/`: repository with JdbcClient, service, controller. `POST /accounts` creates the account and its zero balance row in one transaction. `GET /accounts/{id}`.
4. Errors as `ProblemDetail` from one `@RestControllerAdvice`: 400 validation, 404 unknown id, 409 duplicate code.
5. Tests first: `MoneyTest` (overflow throws, mismatch throws) and `AccountApiIT` with Testcontainers PostgreSQL (create, fetch, duplicate 409, bad currency 400).

Parallel: one builder on Money and MoneyTest, one on the migration and account package, one on the error handler and AccountApiIT.

Verify: `./gradlew build`; paste the test summary. Then explain each new file in one line.

Done when: accounts work over HTTP and all tests pass.

---

## Phase 2A · Posting: schema, endpoints and tests

Tasks
1. `V2__journal.sql`: `journal_entries(id uuid pk, kind text check (kind in ('transfer','entry','reversal')), reverses_id uuid references journal_entries, description text, effective_at timestamptz not null, created_at timestamptz not null default now())`; `journal_lines(id bigserial pk, entry_id uuid not null references journal_entries, account_id uuid not null references accounts, amount bigint not null check (amount <> 0), currency char(3) not null)`; index on `journal_lines(account_id, id)`.
2. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger that fails the commit if an entry's lines don't sum to 0 per currency.
3. A `tally_app` database role with SELECT and INSERT on the journal tables and no UPDATE or DELETE. The app connects as `tally_app`; Flyway runs as the owner. Tell me exactly which config you changed.
4. `posting/`: request and response records, a `PostingService` interface whose Javadoc lists the posting recipe from CLAUDE.md, and `PostingServiceImpl` that throws `UnsupportedOperationException` for now.
5. Endpoints: `POST /transfers`, `POST /entries`, `GET /accounts/{id}/balance`, `GET /accounts/{id}/entries?cursor=&limit=` (keyset paging on line id).
6. Tests that fail until 2B: happy transfer; unbalanced entry gives 422; overdraft gives 422; a raw SQL insert of an unbalanced entry fails at commit; an UPDATE on journal_lines as tally_app is denied.
7. `ConcurrencyIT`: 10 accounts with 10,000 cents each, 50 threads x 200 random transfers through the service. Afterwards: total unchanged, every balance equals the sum of its lines, only insufficient-funds errors allowed. Thread count and seed as constants at the top.

Parallel: one builder on the migration, trigger and role (1 to 3); one on posting records, interface, stub and controllers (4 and 5); one on the tests (6 and 7).

Verify: it compiles; list which tests fail and confirm they fail only because of the stub.

Done when: the schema rules are enforced by PostgreSQL and the tests are ready.

---

## Phase 2B · Posting: the service

Tasks
1. Implement `PostingServiceImpl` following the posting recipe exactly (idempotency comes in Phase 3, so skip that step for now). Keep it readable top to bottom; one method per recipe step is fine.
2. Run the scope-reviewer for money correctness before running the tests.
3. Run `./gradlew build`, then `ConcurrencyIT` 20 times with a small loop script and report the pass count.
4. Experiment, on a branch named `experiment/unsorted-locks`: remove the sorting of account ids before locking, run ConcurrencyIT 5 times, and record in PROGRESS.md what failed and why. Switch back to the main branch afterwards; don't merge the experiment.

Parallel: none. This is the core; do it in the main session.

Verify: all Phase 2A tests pass; the 20-run pass count is pasted.

Done when: ConcurrencyIT passes 20 runs in a row.

---

## Phase 3 · Idempotency and reversals

Tasks
1. `V3__idempotency.sql`: `idempotency_keys(key text pk, request_hash text not null, response_status int, response_body jsonb, created_at timestamptz not null default now())`.
2. `idempotency/IdempotencyStore.claim(key, hash)` returning Claimed, Replay(status, body) or Conflict, using `INSERT ... ON CONFLICT DO NOTHING` then SELECT, inside the caller's transaction. Wire it into PostingServiceImpl as recipe step 2.
3. Require an `Idempotency-Key` header on `POST /transfers`, `/entries` and `/entries/{id}/reverse` (400 if missing). Hash = SHA-256 of the canonical JSON body.
4. `POST /entries/{id}/reverse` posts the mirror-image lines. A unique partial index on `journal_entries(reverses_id)` blocks a second reversal (409).
5. `GET /accounts/{id}/balance?asOf=` sums lines of entries created at or before asOf; add the index this needs.
6. Tests: same key twice gives one entry and an identical response; same key with a different body gives 409; 20 threads sending the same key at once create exactly one entry; reversing twice gives 409; asOf before a transfer returns the old balance.

Parallel: one builder on the idempotency store and its tests; one on reversal and asOf with their tests. The main session wires the store into PostingServiceImpl.

Verify: run the same-key concurrency test 20 times; paste the pass count.

Done when: it passes 20 runs in a row.

---

## Phase 4 · Outbox and Kafka

Tasks
1. `V4__outbox.sql`: `outbox(id bigserial pk, entry_id uuid not null, type text not null, payload jsonb not null, created_at timestamptz not null default now(), published_at timestamptz)`; partial index on id where published_at is null.
2. `outbox/OutboxWriter.write(entry)`, called inside the posting transaction.
3. `outbox/OutboxRelay`: `@Scheduled` every 200 ms. In one transaction, select up to 100 unpublished rows ordered by id `FOR UPDATE SKIP LOCKED`, send each to topic `ledger.entries` keyed by entry_id, wait for each ack, then set published_at. If a send fails, roll back so the rows are retried.
4. Producer: acks=all, idempotence on.
5. A test-only consumer that collects entry ids in a Set and counts duplicates it ignored.
6. Tests with Testcontainers Kafka: post 500 transfers, and within 10 s the consumer holds 500 distinct ids; with two relays running at once, still 500 distinct.
7. In `docs/design.md` under Open questions, record the ordering rule: messages are keyed by entry_id, so Kafka orders per entry, not per account; consumers that need per-account order must sort by the entry's line ids. State that delivery is at-least-once and consumers dedupe on entry_id.

Parallel: one builder on the migration, writer and relay; one on the test consumer and tests.

Verify: paste the test output. Then explain in three sentences why writing to the database and Kafka separately would lose or invent events.

Done when: every entry reaches the consumer exactly once.

---

## Phase 5 · Reconciliation and observability

Tasks
1. `V5`: `reconciliation_drift(account_id, stored, computed, found_at)`.
2. `reconcile/ReconciliationJob`: for every account compare `balances.balance` with `SUM(journal_lines.amount)`; write drift rows; publish a gauge `ledger.reconciliation.drift.accounts`. Expose `POST /admin/reconcile` behind an admin API key read from config.
3. Test: corrupt one balance through the owner connection, run the job, assert exactly that account is flagged.
4. Metrics: Timer `ledger.posting.duration` around posting, Counter `ledger.postings`, Timer `ledger.lock.wait` around the `SELECT ... FOR UPDATE`. Add entry_id as a span attribute in the posting trace and in the relay's publish.
5. Outbox health: Gauge `ledger.outbox.pending` (unpublished rows) and Gauge `ledger.outbox.oldest.age.seconds`, so a stuck relay shows up on the dashboard.
6. Structured JSON logs (Spring Boot's structured logging) that include trace id and span id, and put entry_id and the idempotency key in the log context while posting and publishing, so one payment attempt can be followed across log lines.
7. `ops/grafana/ledger.json`: one dashboard with those metrics, provisioned into the otel-lgtm container.

Parallel: one builder on reconciliation and its test; one on metrics, logging, tracing and the dashboard.

Verify: start the app, post 20 transfers with a curl loop, and tell me exactly where in Grafana to find one transfer's trace, its publish and its log lines by entry id (the trace and the publish are two separate traces, linked by entry_id).

Done when: both traces and the log lines can be found by the same entry id.

---

## Phase 6 · Failure testing

Use Toxiproxy through `org.testcontainers:testcontainers-toxiproxy`, matching our Testcontainers version.

Tasks
1. In `src/test/java/.../failure/`, a base class that runs PostgreSQL and Kafka behind Toxiproxy and points the app at the proxies. Every test must finish by asserting: total money per currency unchanged, every balance equals its lines, each idempotency key has at most one entry, the consumer saw each entry once.
2. `DbConnectionDropIT`: during 200 concurrent transfers, cut the database link for 3 s (bandwidth 0 both ways), then restore. Clients retry failures with the same key until they succeed.
3. `SlowDbIT`: add 2,000 ms latency; with a 1 s transaction timeout, requests fail with a clean 503 problem response and leave no partial entries.
4. `KafkaOutageIT`: cut Kafka for 60 s while posting continues; posting keeps returning 2xx; after restoring, the outbox drains to zero within 30 s.
5. `RetryStormIT`: 50 identical requests with one key at once give exactly one entry and 49 identical replays.
6. `scripts/crash-test.sh` (manual, not in the build): start the jar, run load/transfers.js with idempotency keys derived from the iteration number, kill -9 the app mid-run, restart, replay the same iterations, call /admin/reconcile, print the drift count.
7. When a test fails, show me the failure, your diagnosis and a proposed fix before changing production code; apply it only after I agree.
8. `docs/failure-table.md`: scenario | what failed first | root cause | fix | test name.

Parallel: the main session writes the base class first. Then four builders write tests 2 to 5 in parallel, one file each. The main session runs them one at a time.

Verify: paste the output of all four tests and of crash-test.sh.

Done when: all five scenarios pass and the failure table is filled.

---

## Phase 7 · Random simulation tests

Tasks
1. `src/test/.../simulation/LedgerModel`: an in-memory model (map of balances) with the same rules: overdraft, idempotency, reverse once.
2. `SimulationIT`: for seeds 1..N (system property, default 20), 500 operations each from a weighted generator: open account 5%, transfer 60%, multi-line entry 15%, reverse 10%, retry a previous request 10%. Send each to the HTTP API and to the model; after each, assert the same outcome (success or error code) and the same balances. At the end, assert the invariants.
3. On failure print the seed, the operation index and the last 10 operations, replayable with `-Dseed=X`.
4. A Gradle task `simulationTest` that runs separately from the normal build.

Parallel: one builder on LedgerModel, one on the generator and Gradle task; the main session writes SimulationIT that joins them.

Verify: run with N=20 (10,000 operations) and report the count and run time.

Done when: 10,000 operations pass and the count is recorded in PROGRESS.md.

---

## Phase 8 · Load test and one improvement

Tasks
1. `load/transfers.js`: setup seeds 1,000 funded accounts (add a seed script); ramp to 50 virtual users over 1 min, hold 3 min; deterministic Idempotency-Key per iteration; threshold http_req_failed < 1%.
2. `load/hot-account.js`: same, but every transfer pays account 1.
3. `scripts/load.sh`: start the app with a quiet production-like profile, run both scenarios, save k6 JSON summaries to `load/results/<date>/`.
4. Run both. Report requests per second, p50, p95, p99, error rate, my machine's CPU model and RAM (read from the OS) and the connection pool size.
4b. Saturation run on scenario A: a stepped ramp (for example 10, 25, 50, 100, 200 virtual users, a few minutes each; choose the steps from what the machine can take) to find the load at which p95 starts to climb. Record that knee and what the app, database and pool looked like there.
5. Propose ONE change most likely to raise scenario A's throughput, apply it on a branch, re-run, and show before and after. Explain the hot-account result in plain words.

Parallel: one builder per k6 script; the main session writes load.sh and runs everything.

Verify: paste the k6 summaries.

Done when: a results table with real numbers and test conditions is in PROGRESS.md.

---

## Phase 9 · Write-up

Use only numbers found in load/results/, docs/failure-table.md, PROGRESS.md and test output; never invent one.

Tasks
1. `README.md`: one-paragraph pitch; architecture as a Mermaid diagram; one-command run; curl examples; results table; link to the failure table; "What broke" drawn from the failure table and git log; a "Design influences" section linking Wealthsimple's "Inside the Book of Record" and Shopify's "10 Tips for Building Resilient Payment Systems" and "Building Resilient GraphQL APIs Using Idempotency", saying only what those posts support; a "How I used AI" heading left as a TODO for me.
2. `docs/adr/0001` to `0006`: plain SQL over JPA, sorted row locks over SERIALIZABLE, outbox over dual writes, long cents over BigDecimal, append-only entries with reversals over updates, idempotency key semantics (hash, replay, conflict). Each: context, decision, consequences, alternatives.
3. `docs/demo-script.md`: a 2-minute demo order (curl requests, a Grafana trace, the failure tests running).

Parallel: one builder on the README, one on the six ADRs and the demo script.

Verify: the scope-reviewer checks the README and ADRs against the code and flags any claim the code doesn't support.

Done when: a friend could clone it, run one command and post a transfer within 10 minutes.

Stretch (only if I ask): holds (authorise, capture, void), a Kotlin version of posting, multi-currency entries, a circuit breaker and load shedding on the posting endpoints, idempotency-key expiry (24 h).
