# tally progress

| Phase | Status | Branch | PR | Notes |
| --- | --- | --- | --- | --- |
| 0 Tools and scaffolding | done, merged 2026-10-17 | phase-0-scaffolding | https://github.com/jenishmsoni45/tally/pull/1 | 10 commits on the branch, 1 on main; CI build passed in 3m13s |
| 1 Money and accounts | done, merged 2026-10-17 | phase-1-money-accounts | https://github.com/jenishmsoni45/tally/pull/3 | 9 commits on the branch; `./gradlew clean build` passed, 27 tests |
| 2A Posting: schema, endpoints, tests | done, merged 2026-10-18 | phase-2a-posting-schema | https://github.com/jenishmsoni45/tally/pull/4 | 13 commits on the branch; 51 tests, 47 pass; the 4 failures are the tests PLAN.md says wait for 2B, so CI on this PR is red by design |
| 2B Posting: the service | in progress | phase-2b-posting-service | | |
| 3 Idempotency and reversals | not started | | | |
| 4 Outbox and Kafka | not started | | | |
| 5 Reconciliation and observability | not started | | | |
| 6 Failure testing | not started | | | |
| 7 Random simulation tests | not started | | | |
| 8 Load test and one improvement | not started | | | |
| 9 Write-up | not started | | | |

## Measured results (real runs only, with date and conditions)

Phase 0, 2026-10-17, Mac (darwin arm64), Java 25.0.1, Gradle 9.8.1, Docker 29.8.2, k6 v2.3.0. Conditions: local machine, images already pulled for the second run.

| What | Result |
| --- | --- |
| `./gradlew build` first run (includes Gradle download and image pulls) | BUILD SUCCESSFUL in 1m 15s, 1 test, 0 failures |
| `./gradlew clean build` after review fixes | BUILD SUCCESSFUL in 16s, 1 test, 0 failures |
| `./gradlew bootRun`, first run: `Started TallyApplication` | 33.6 s (includes starting the three containers) |
| `curl -s localhost:8080/actuator/health` (final code) | `{"groups":["liveness","readiness"],"status":"UP"}` |
| Containers after start | postgres:18.6, apache/kafka:4.3.1, grafana/otel-lgtm:0.37.0, all healthy, ports bound to 127.0.0.1 |
| `git ls-files` matching CLAUDE/PLAN/PROGRESS/.claude | nothing |

Phase 1, 2026-10-17, same machine and tool versions as Phase 0. Conditions: local machine, images already pulled.

| What | Result |
| --- | --- |
| `./gradlew clean build` (final code) | BUILD SUCCESSFUL in 21s, 27 tests, 0 failures, 0 skipped |
| Tests by class | `MoneyTest` 16, `AccountApiIT` 10, `TallyApplicationTests` 1 |
| `AccountApiIT.concurrentCreatesOfOneCodeSucceedExactlyOnce` | 8 simultaneous creates of one code: one 201, seven 409, one account row and one balance row. Run only as part of the full builds above, not looped. |

Phase 2A, 2026-10-17, same machine and tool versions as Phase 0. Conditions: local machine, images already pulled.

| What | Result |
| --- | --- |
| `./gradlew clean compileJava compileTestJava` (final code) | BUILD SUCCESSFUL |
| `./gradlew test` (final code) | 51 tests completed, 4 failed, BUILD FAILED in 20s |
| Tests by class | `MoneyTest` 16, `AccountApiIT` 10, `JournalSchemaIT` 11, `BalanceApiIT` 5, `TallyApplicationTests` 1, `PostingApiIT` 7 (3 fail), `ConcurrencyIT` 1 (fails) |
| The 4 failures | `ConcurrencyIT.concurrentTransfersKeepTheLedgerBalanced` throws `UnsupportedOperationException: Posting is implemented in Phase 2B`. `PostingApiIT` happy transfer, unbalanced 422 and overdraft 422 get a 500. The server log of the overdraft test shows the same exception; the other two call the same stub. |
| Tests that PLAN.md lists as waiting for 2B but pass now | the raw-SQL unbalanced insert and the denied UPDATE, in `JournalSchemaIT`: they test PostgreSQL, not the service |
| `bootRun` (before the review fixes to V2) | `/actuator/health` UP within 36 s (polled every 3 s); `flyway_schema_history` versions 1, 2, 3 all successful; `tally_app` is not a superuser; all 10 pooled application sessions ran as `tally_app` |

## Things that broke and how they were fixed

- Phase 0, the `builder` and `scope-reviewer` agent types were not registered in the session (the session started before `.claude/` was moved to the repo root). Used general-purpose agents with the same rules written into the prompt. Restart Claude Code in this folder so the named agents load.
- Phase 0, the security-review skill diffs against `origin/HEAD`, which does not exist while the remote is empty. The security review was done by hand on `git diff main...HEAD` instead.
- Phase 0, code review found compose ports bound to all interfaces with default credentials (fixed: 127.0.0.1), CI building every branch push twice (fixed: push only on main), no datasource settings in application.yaml (fixed: env-driven with throwaway defaults), no Gradle distribution checksum (fixed), and package-info comments that only restated the package name (fixed).

- Phase 1, the first `AccountApiIT` run had 4 failures: `CreateAccountRequest` used a primitive `boolean allowNegative`, and Jackson 3 answers 400 when that field is omitted, before validation runs. Fixed by boxing it and treating an omitted flag as false.
- Phase 1, the security-review skill again failed on the missing `origin/HEAD`. Fixed locally with `git remote set-head origin main`, which changes only a local reference.
- Phase 1, code review found a duplicated row mapper, the currency pattern defined twice, no transaction timeout, no database check on currency, and a duplicate-code test that did not test the race it claimed to. All fixed. The `builder` and `scope-reviewer` agent types were still not registered, so the scope review used a general-purpose agent with the same rules.

- Phase 2A, a `@ServiceConnection` Postgres container (and Compose support) would have made the app connect as the owner, not `tally_app`. Fixed: the test container sets the datasource and Flyway properties itself (`DynamicPropertyRegistrar`), and `compose.yaml` marks the postgres service with `org.springframework.boot.ignore`. `JournalSchemaIT.theApplicationConnectsAsTallyApp` and the `bootRun` check above prove it.
- Phase 2A, code review found real gaps, all fixed: a line's currency was not tied to its account's currency (composite foreign key added), an entry with no lines passed the balance check (second deferred trigger added), and Jackson accepted `100.75` as an `amount` and cut it to 100 (found by a test, fixed with `accept-float-as-int: false`).
- Phase 2A, `PostingApiIT.aLineWithAmountZeroIs400` failed after it had passed: both lines were zero, so the order of the validation errors was not fixed. My test bug; fixed by making only one line invalid, not by loosening the assertion.
- Phase 2A, the `builder` and `scope-reviewer` agent types were registered this time and the scope review used the real `scope-reviewer`. The security-review skill ran normally (it needed `origin/HEAD`, set in Phase 1).

## Ideas (out of scope for now)

- Test config starts the whole otel-lgtm stack for every integration-test context; image tags are duplicated between compose.yaml and TestcontainersConfiguration. Revisit when tests get slow (Phase 5 or 6).
- Pin GitHub Actions to commit SHAs instead of major tags.
- Kafka and otel-lgtm have no volumes, so their data is lost when the containers are removed.
- `.gitattributes` and `spring.jackson.time-zone: UTC` are extras from the generator and builder; harmless, not in the plan.
- Hikari pool size 10 and connection timeout 2000 ms are starting values; measure in Phase 8.
- Add a catch-all 500 `ProblemDetail` handler (logged, no internals) together with the 503 mapping for pool and statement timeouts, so unexpected errors are never non-problem bodies. No Phase 1 endpoint can reach that path yet, so it cannot be tested now.
- Trim or restrict account `code` at the edge: `cash` and `cash ` are different codes today.
- Reject unknown JSON fields and an explicit `allowNegative: null` instead of treating them as false.
- Narrow the `DuplicateKeyException` catch in `AccountService` to the `code` constraint, and return a dedicated `AccountResponse` record instead of the `Account` record.
- Phase 2B must reject a transfer whose `fromAccountId` equals `toAccountId`, and must insert an account's lines while holding that account's balance row lock (the paging comment in `BalanceRepository` depends on it).
- Watch `ConcurrencyIT` in the 20-run loop: 50 threads share a pool of 10 with a 2000 ms connection timeout, and a pool timeout counts as an unexpected failure. If it flakes, show the failure and a diagnosis before changing anything.
- PLAN.md names `V3__idempotency.sql`, `V4__outbox.sql` and `V5` for later phases, but `V3__app_role.sql` is taken. Later phases should use the next free version number; update PLAN.md through a PR when convenient.
- `tally_app` has no default privileges. Every later migration must grant it what it needs: Phase 3 `idempotency_keys` needs UPDATE, Phase 4 the outbox needs UPDATE on `published_at` and sequence usage, Phase 5 the drift table needs INSERT.
- The `tally_app` password is set only when V3 creates the role. Rotate it later with `ALTER ROLE`.
- Commit `31351ac` bundles two related V2 changes (composite currency foreign key and the empty-entry trigger). Not split: the branch is unpushed, but a rebase needs approval.
- Extras added beyond the literal 2A task list, all covered by tests: `V3__app_role.sql` as its own migration, the `(id, currency)` unique key and composite foreign key, the empty-entry trigger, the `journal_lines(entry_id)` index (the balance trigger needs it), the `NonZero` constraint, `UnprocessableException`, `BalanceApiIT`, `JournalSchemaIT`, `LedgerFixtures`.
- Until 2B, `POST /transfers` and `POST /entries` answer a bare 500 (not a ProblemDetail). The catch-all 500 handler idea above covers it.
