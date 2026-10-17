# tally progress

| Phase | Status | Branch | PR | Notes |
| --- | --- | --- | --- | --- |
| 0 Tools and scaffolding | done, merged 2026-10-17 | phase-0-scaffolding | https://github.com/jenishmsoni45/tally/pull/1 | 10 commits on the branch, 1 on main; CI build passed in 3m13s |
| 1 Money and accounts | done locally, awaiting push approval | phase-1-money-accounts | | 9 commits on the branch; `./gradlew clean build` passed, 27 tests |
| 2A Posting: schema, endpoints, tests | not started | | | |
| 2B Posting: the service | not started | | | |
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

## Things that broke and how they were fixed

- Phase 0, the `builder` and `scope-reviewer` agent types were not registered in the session (the session started before `.claude/` was moved to the repo root). Used general-purpose agents with the same rules written into the prompt. Restart Claude Code in this folder so the named agents load.
- Phase 0, the security-review skill diffs against `origin/HEAD`, which does not exist while the remote is empty. The security review was done by hand on `git diff main...HEAD` instead.
- Phase 0, code review found compose ports bound to all interfaces with default credentials (fixed: 127.0.0.1), CI building every branch push twice (fixed: push only on main), no datasource settings in application.yaml (fixed: env-driven with throwaway defaults), no Gradle distribution checksum (fixed), and package-info comments that only restated the package name (fixed).

- Phase 1, the first `AccountApiIT` run had 4 failures: `CreateAccountRequest` used a primitive `boolean allowNegative`, and Jackson 3 answers 400 when that field is omitted, before validation runs. Fixed by boxing it and treating an omitted flag as false.
- Phase 1, the security-review skill again failed on the missing `origin/HEAD`. Fixed locally with `git remote set-head origin main`, which changes only a local reference.
- Phase 1, code review found a duplicated row mapper, the currency pattern defined twice, no transaction timeout, no database check on currency, and a duplicate-code test that did not test the race it claimed to. All fixed. The `builder` and `scope-reviewer` agent types were still not registered, so the scope review used a general-purpose agent with the same rules.

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
