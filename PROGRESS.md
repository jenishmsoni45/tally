# tally progress

| Phase | Status | Branch | PR | Notes |
| --- | --- | --- | --- | --- |
| 0 Tools and scaffolding | done, merged 2026-10-17 | phase-0-scaffolding | https://github.com/jenishmsoni45/tally/pull/1 | 10 commits on the branch, 1 on main; CI build passed in 3m13s |
| 1 Money and accounts | done, merged 2026-10-17 | phase-1-money-accounts | https://github.com/jenishmsoni45/tally/pull/3 | 9 commits on the branch; `./gradlew clean build` passed, 27 tests |
| 2A Posting: schema, endpoints, tests | done, merged 2026-10-18 | phase-2a-posting-schema | https://github.com/jenishmsoni45/tally/pull/4 | 13 commits on the branch; 51 tests, 47 pass; the 4 failures are the tests PLAN.md says wait for 2B, so CI on this PR is red by design |
| 2B Posting: the service | done, merged 2026-10-18 | phase-2b-posting-service | https://github.com/jenishmsoni45/tally/pull/5 | 9 commits on the branch; 57 tests, 0 failures; `ConcurrencyIT` passed 20 of 20 runs; the unsorted-locks experiment failed 5 of 5 (below) |
| 3 Idempotency and reversals | done locally, awaiting push approval | phase-3-idempotency-reversals | | 13 commits on the branch (with this one); 88 tests, 0 failures; the same-key test passed 20 of 20 runs (below) |
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

Phase 2B, 2026-10-18, same machine and tool versions as Phase 0. Conditions: local machine, images already pulled.

| What | Result |
| --- | --- |
| `./gradlew clean build` (final code) | BUILD SUCCESSFUL in 32s, 57 tests, 0 failures, 0 skipped |
| Tests by class | `MoneyTest` 16, `AccountApiIT` 10, `JournalSchemaIT` 11, `BalanceApiIT` 5, `TallyApplicationTests` 1, `PostingApiIT` 13, `ConcurrencyIT` 1 |
| `ConcurrencyIT` in a loop of 20 (`--rerun` each time, final code) | passed 20 of 20. Seed 20261017, 50 threads x 200 transfers. Last run: 8790 succeeded, 1210 refused for insufficient funds. |
| `ConcurrencyIT` in a loop of 20, before the lock-order comparator fix | passed 20 of 20 (8649 succeeded and 1351 refused in the last run) |
| Experiment `experiment/unsorted-locks` (balance locks taken in request order, not sorted), `ConcurrencyIT` 5 runs | passed 0 of 5. Last run: 4169 succeeded, 738 refused for funds, so 5093 of 10000 transfers failed with unexpected errors. |

The experiment: the only change was `netChangePerAccount` using a `LinkedHashMap` instead of the sorted `TreeMap`, so `lockBalances` locked accounts in the order the lines arrived. The failures in the last run were PostgreSQL `ERROR: deadlock detected` on `select balance from balances where account_id = ? for update` (for example "Process 80 waits for ShareLock on transaction 847; blocked by process 82. Process 82 waits for ShareLock on transaction 849; blocked by process 80"), plus `CannotCreateTransactionException: Could not open JDBC Connection`. Why: with 10 accounts and 50 threads, one transfer A to B and another B to A each lock their first account and then wait for the other's, and neither can proceed. PostgreSQL breaks each cycle by aborting one transaction. The connection errors are most likely a side effect (aborted and waiting transactions hold pooled connections while the pool of 10 has a 2000 ms timeout); I did not verify that separately. With sorted locking the same test passed 40 of 40 runs. The experiment branch is local only, one commit, and was not merged or pushed.

Phase 3, 2026-10-18, same machine and tool versions as Phase 0. Conditions: local machine, images already pulled.

| What | Result |
| --- | --- |
| `./gradlew clean build` (final code) | BUILD SUCCESSFUL in 37s, 88 tests, 0 failures, 0 skipped |
| Tests by class | `MoneyTest` 16, `AccountApiIT` 10, `BalanceApiIT` 9, `JournalSchemaIT` 12, `PostingApiIT` 13, `IdempotencyIT` 10, `IdempotencyStoreIT` 7, `ReversalIT` 9, `ConcurrencyIT` 1, `TallyApplicationTests` 1 |
| `IdempotencyIT.twentyThreadsSendingTheSameKeyCreateExactlyOneEntry` in a loop of 20 (`--rerun` each time, final code) | passed 20 of 20. 20 simultaneous HTTP requests with one key: all got 201 with the same body, one entry, one line per account. |
| `ReversalIT.anEntryReversedByEightRequestsAtOnceIsReversedOnceAndTheRestAreConflicts` in a loop of 20 (final code) | passed 20 of 20. One 201 and seven 409, never a 422. |
| Mutation check: `requireNotReversedYet` moved after the overdraft check | `ReversalIT` 2 of 8 failed (the second-reversal-is-409 test and the eight-at-once test), so those tests do depend on the order. Reverted. |
| `EXPLAIN` of the asOf query on a throwaway PostgreSQL 18.6 container, 30,000 entries x 2 lines on one account, tables analyzed, plans only (no timings) | asOf 29,900 minutes back (100 of 30,000 entries match): the planner used `journal_entries_created_at_idx`. asOf 15,000 minutes back and asOf 100 minutes back (most entries match): it scanned the account's lines and did not use the index. So the index helps only an early asOf. The test was thrown away, not committed. |

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

- Phase 2B, code review (10 findings) and the scope-reviewer's pre-test and final reviews. Fixed: lock order used `UUID.compareTo`, which is signed and differs from PostgreSQL's uuid order, so a later `order by account_id for update` could deadlock against a posting (now `Comparator.comparing(UUID::toString)`); no test covered netting of repeated accounts in one entry or the service's own transfer checks (added); the insufficient-funds message could print a wrong figure (reworded). Declined on purpose: `IllegalArgumentException` for direct-caller preconditions and no service-level empty-lines check (the controller validates, and the deferred triggers are the backstop); the `@AssertTrue` error is reported under the field name `toAccountDifferent`; a "no transaction timeout" claim was wrong (the 5 s default is set in application.yaml); a negative balance on a no-overdraft account cannot happen because `allow_negative` is never updated.
- Phase 2B, first `PostingApiIT` run after adding the service passed at once. The `builder` agent was not used (the plan says none for this phase); the `scope-reviewer` ran twice (before the tests, as the plan requires, and at the phase end).
- Phase 2B, the experiment's failed runs took far longer than passing ones: the 5 failed runs did not finish inside a 10-minute foreground limit and were moved to the background.

- Phase 3, adding a constraint (`@Pattern`) to the `Idempotency-Key` header made Spring validate the whole controller method, so a bad request body came back as `HandlerMethodValidationException` without the `errors` list, and `PostingApiIT.aLineWithAmountZeroIs400` failed. Fixed by handling that exception in `ApiExceptionHandler` and listing the violations in the same shape.
- Phase 3, a test that sent the key `café` got 201: the test client replaced the non-ASCII character with `?`, so the server saw `caf?`. A client limitation, not a server bug; the case was removed and `two words` and a tab cover the pattern. The `IdempotencyStoreIT` first expectation compared jsonb text with the original text, and jsonb adds whitespace; the assertion now ignores whitespace.
- Phase 3, a reversal that came second was first answered 422 (overdraft) instead of 409, because the overdraft check ran before the already-reversed check. The reversed check now runs right after the balance locks are held, so a competing reversal is always visible and the answer is always 409.
- Phase 3, code review (10 findings). Fixed: `saveResponse` now fails if no row was updated; unused pattern bindings removed; `ConcurrencyIT` keys carry a per-run prefix; limits documented (asOf and the request hash). Not fixed, on purpose: reversing a reversal is allowed (documented and tested); `Claim.Replay` and `PostingResult` carry a status that is always 201, because the plan specifies `Replay(status, body)` and it makes replay faithful by construction; class-level validation errors are not listed by the new handler (no such constraint exists; the older handler behaves the same); the migration is `V4` because `V3` is taken (already under Ideas). The security review found nothing. The scope-reviewer found no missing plan item and no unlisted work except the key format rule and the handler, both explained here.
- Phase 3, decision: only successful postings save their idempotency key. A refused posting (422, 404) rolls back with its key, so a retry with the same key runs again. Tested in `IdempotencyIT.aRefusedRequestDoesNotUseUpItsKey`.
- Phase 3, the hash is the SHA-256 of the operation name and the bound request serialized again, not of the raw body (PLAN.md says "canonical JSON body"). Requests the server reads identically hash the same.

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
- PLAN.md names `V3__idempotency.sql`, `V4__outbox.sql` and `V5` for later phases, but `V3__app_role.sql` is taken. Later phases should use the next free version number; update PLAN.md through a PR when convenient.
- `tally_app` has no default privileges. Every later migration must grant it what it needs: Phase 3 `idempotency_keys` needs UPDATE, Phase 4 the outbox needs UPDATE on `published_at` and sequence usage, Phase 5 the drift table needs INSERT.
- The `tally_app` password is set only when V3 creates the role. Rotate it later with `ALTER ROLE`.
- Commit `31351ac` bundles two related V2 changes (composite currency foreign key and the empty-entry trigger). Not split: the branch is unpushed, but a rebase needs approval.
- Extras added beyond the literal 2A task list, all covered by tests: `V3__app_role.sql` as its own migration, the `(id, currency)` unique key and composite foreign key, the empty-entry trigger, the `journal_lines(entry_id)` index (the balance trigger needs it), the `NonZero` constraint, `UnprocessableException`, `BalanceApiIT`, `JournalSchemaIT`, `LedgerFixtures`.
- Any code that locks balances (Phase 3 reversals, Phase 5 reconcile if it ever locks) must use the same order as `PostingServiceImpl.LOCK_ORDER` (PostgreSQL uuid order). `order by account_id for update` in SQL matches it. No test pins this order yet.
- A posting does one query per account and per line (account lookup, lock, balance update, line insert; up to 100 lines). Batching would shorten lock hold time. Measure in Phase 8 before changing.
- `requireBalanced` sums in request order, so a valid entry whose running sum overflows (for example MAX, MAX, -MAX, -MAX) is refused with a 422. Safe, but could be summed differently if it ever matters.
- `BalanceRepository.updateBalance` ignores the updated-row count. The row is locked in the same transaction so zero rows is impossible today.
- Idempotency keys are global and never expire: no caller scope and no cleanup of old rows. Add both when authentication exists. Consider also replaying refused requests if clients need the same 422 back.
- `asOf` filters on `created_at`, which is the transaction start time, so a read while an earlier-started posting is still committing can miss it, and the same asOf answers differently once it commits. A commit-ordered cut would need another key. The `created_at` index serves only an early asOf (see the EXPLAIN result); measure in Phase 8 whether the asOf query needs another design.
- The log line for a refused posting is still missing; refused postings log nothing.
- `journal_entries.kind` and `reverses_id` are not tied together: nothing stops a `reversal` row without `reverses_id`, or the reverse. Add a check in a later migration if wanted.
- The validation handlers list field errors only, not class-level errors.
- Commit `4da8e9c` bundles the service-side transfer check, a lock-order comment that `3f4dbcb` later corrected, and a reworded message. Not split: a rebase needs approval.
