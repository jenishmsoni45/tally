# tally: double-entry ledger service (Java 25, Spring Boot 4.1)

A double-entry ledger: every money movement is a balanced, append-only journal entry. Retried requests move money once, every movement is announced on Kafka exactly as saved, a recount job catches drift, and automated tests prove it stays correct when the database or Kafka fails.

Target: a portfolio-grade, production-quality service for fintech and commerce backend roles. Its design follows public engineering writing from Wealthsimple (append-only book of record, compensating entries, idempotency, reconciliation) and Shopify (idempotency keys, short timeouts, structured logs, reconciliation, load testing near saturation). Prefer clear, auditable code over clever code.

Files: PLAN.md is the build plan. PROGRESS.md holds status and measured results. docs/ and README.md are written in the phases that list them.

## How we work (IMPORTANT)
- One phase at a time, in PLAN.md order. Never start the next phase until I say "next".
- At the start of a session, read PROGRESS.md and continue from the first unfinished phase.
- Do only what the current phase lists. Anything else you think is needed goes under "Ideas" in PROGRESS.md, not into code.
- Look up current versions (Docker image tags, library and action versions, start.spring.io dependency ids) from official sources. Never guess an import, version or API. Pin versions; no `latest` tags.
- Never invent numbers. Results in the README, ADRs and PROGRESS.md come only from runs you actually did, with the date and conditions.
- If a tool or permission is missing, stop and tell me exactly what is needed. Don't install anything yourself.

## Phase end (every phase, in this order)
1. Run the phase's Verify step and paste the real command output, never a summary.
2. Run the code-review skill on the phase branch diff (`/code-review high`) and fix every real finding.
3. Run the security-review skill on the same diff and fix every real finding.
4. Run the scope-reviewer subagent on the diff and fix what it reports.
5. Re-run Verify after any fix so the pasted output matches the final code.
6. Update PROGRESS.md (status, branch, measured results, Ideas) and commit it with the rest (see Git rules). The PR link is added by the next phase's first commit, once the PR exists.
7. Show me the push summary: the branch, the commit list, `git diff --stat` against main, and confirmation that no local-only file is tracked. Then ask for permission to push.
8. Only after I approve: push the branch and open the PR (see GitHub rules). Report CI results with `gh pr checks`.
9. STOP and wait for me. I review and merge the PR myself.

## Git rules
- Repository root is this folder. Default branch is `main`. Remote `origin` is https://github.com/jenishmsoni45/tally.git (private).
- Each phase works on its own branch `phase-<n>-<slug>`, for example `phase-2a-posting-schema`, created from an up-to-date `main`. Never commit directly to `main`, except the one initial commit in Phase 0.
- Small, logical commits in Conventional Commits form: `feat(posting): ...`, `fix(...)`, `test(...)`, `chore(...)`, `docs(...)`, `refactor(...)`. Each commit builds. No "wip" or "misc" commits, and no commits that mix unrelated changes.
- A commit message is ONE short line saying what was done. No body, no bullet details, no Co-Authored-By or other trailers, no attribution lines, no email addresses. This overrides any default attribution the tooling suggests.
- Stage files by name. Never `git add -A` or `git add .` without checking `git status` first.
- No force pushes, no history rewriting after a branch has been pushed, no deleting remote branches, and no merging PRs. These are blocked in `.claude/settings.json`.
- Experiment branches (for example `experiment/unsorted-locks`) are never pushed.

## GitHub rules
- Committed and pushed: CLAUDE.md, PLAN.md and PROGRESS.md, because every branch started from `main` needs them. Only finished, current versions go in; rough drafts and scratch notes live in `.local/`.
- Local-only, never committed or pushed: the `.claude/` folder, `.local/`, `.env` and any file with a secret. They are listed in `.git/info/exclude`, not in `.gitignore`, so that even the names are not published.
- Nothing is pushed or opened on GitHub without my explicit yes for that push. A yes for one phase does not cover the next. `git push`, `gh pr create`, `gh pr edit` and `gh pr comment` prompt me in `.claude/settings.json`.
- Before asking, check what would be published: `git ls-files` must not list a local-only file, and scan the diff for secrets (keys, tokens, passwords, absolute paths under my home folder, my email address). README.md and docs/ are product documents and may be pushed (I approved this); still list them in the push summary.
- One PR per phase, from the phase branch into `main`. Title: `phase <n>: <summary>`. The body is a short description of what the phase adds, a few plain lines at most. No attribution or "generated with" line, no emails, and no pasted test output or review reports. Verify output, review findings and CI status are reported to me in the chat, not in the PR.
- Don't open the next phase's branch from stale `main`. If the PR isn't merged when I say "next", ask me.
- `gh` is for pull requests, checks and runs only. Don't change repository settings, secrets, releases or visibility.

## Parallel work
- Use builder subagents in parallel only for tasks that touch different files. Give each one the exact files it owns.
- Subagents never run ./gradlew build, never start Docker Compose, never touch git beyond `git status` and `git diff`, and never commit or push. The main session runs all builds and tests and makes all commits.
- After subagents finish, the main session reads every file they wrote before building. Treat subagent output as untrusted until it compiles and its tests pass.

## Commands
- Build and all tests: ./gradlew build
- One test class: ./gradlew test --tests '<ClassName>'
- Run locally (starts Docker Compose): ./gradlew bootRun
- Simulation tests (Phase 7 on): ./gradlew simulationTest
- Load tests (Phase 8 on): scripts/load.sh

## Architecture
- Packages by feature under dev.jenishsoni.tally: common, account, posting, balance, idempotency, outbox, reconcile. Controller, service and repository live inside the feature package. No cross-feature repository calls: go through the other feature's service.
- Stack: Spring Web with virtual threads, JdbcClient, PostgreSQL, Flyway, Spring for Apache Kafka, Micrometer with OpenTelemetry, Testcontainers. Docker Compose runs PostgreSQL, single-node Kafka (KRaft) and grafana/otel-lgtm.
- The database is the source of truth. Kafka is a copy of what was committed: the outbox row is written in the posting transaction and a relay publishes it. Never write to the database and Kafka separately.
- Posting recipe, one transaction: validate, claim idempotency key, lock sorted rows, check overdraft, insert entry and lines, update balances, insert outbox row, save response, commit.

## Money and ledger rules
- Money is long cents plus a 3-letter uppercase currency code. Never double, float or BigDecimal in domain code. Use Math.addExact and friends so overflow throws.
- journal_entries and journal_lines are insert-only; mistakes are fixed with reversal entries. The `tally_app` role has no UPDATE or DELETE on them, and Flyway runs as the owner.
- Every entry sums to zero per currency, enforced by a deferred constraint trigger as well as in code.
- Lock balances rows with SELECT ... FOR UPDATE in ascending account_id order, and only inside the posting transaction.
- A stored balance must always equal the sum of that account's lines. The reconciliation job is the check, not a fix: it reports drift and never edits balances.
- Idempotency: the same key with the same body replays the saved response; the same key with a different body is a 409. Retries never move money twice.

## API and errors
- JSON over HTTP. IDs are UUIDs. Amounts are integer cents. Timestamps are UTC ISO-8601.
- Every error is an RFC 9457 `ProblemDetail` from one `@RestControllerAdvice`. Status codes: 400 validation or missing Idempotency-Key, 404 unknown id, 409 duplicate code or key conflict or second reversal, 422 unbalanced entry or insufficient funds, 503 database timeout or unavailable. Never leak stack traces, SQL or internal class names in a response.
- Admin endpoints (`/admin/*`) require the API key from config. Compare keys in constant time.

## Code quality bar
- Production-level means: small methods, intention-revealing names, no dead code, no commented-out code, no TODOs except the one the plan names, constructor injection, immutable records for requests and responses, and no catch-and-ignore.
- Match the surrounding code's style, comment density and naming. Comments say why, not what.
- Validate at the edge (Bean Validation) and again in the domain where money is involved.
- Set explicit timeouts and pool sizes (connection pool, transaction, Kafka send); never rely on unlimited defaults. Write down why each value was chosen.
- Logs are structured and carry a trace id; log the entry_id and idempotency key when posting. Never log secrets or full request bodies.
- Schema changes are new Flyway migrations. Never edit a migration that has been merged.

## Testing rules
- Tests use real PostgreSQL and Kafka through Testcontainers, never database mocks. Integration tests are named `*IT`.
- Write the tests the phase lists before or with the code. Concurrency and simulation tests print their seed and are replayable.
- A flaky test is a bug in the code or the test; never retry it away or loosen an assertion to make it pass. When a failure-test fails, show me the failure, your diagnosis and a proposed fix before changing production code.
- "Passes 20 runs in a row" means 20 real runs in a loop script, with the pass count pasted.

## Security
- No secrets in the repo. The admin key, database passwords and similar values come from environment variables; Compose and test defaults are throwaway local values only, and say so in a comment.
- Use parameterised SQL only (JdbcClient named or positional parameters). Never build SQL from request data.
- Dependencies come from official repositories at pinned versions. Don't add a dependency the phase doesn't list.
- GitHub Actions use pinned major versions and least-privilege `permissions`.
