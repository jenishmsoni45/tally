#!/usr/bin/env bash
# Manual crash test, not part of the build. Starts the jar, runs transfers whose idempotency keys come from the
# iteration number, kills the app with kill -9 in the middle of the run, restarts it, replays the same iterations
# and runs the recount. A crash must leave no drift, and the replay must not move any money twice.
#
# Needs Docker, k6, jq and openssl; uses the Docker Compose stack in compose.yaml and port 8080.
# ITERATIONS, VUS and KILL_AT override the defaults below.
set -euo pipefail

cd "$(dirname "$0")/.."

ITERATIONS=${ITERATIONS:-5000}
VUS=${VUS:-20}
# The app is killed once this many of the run's transfers have committed, so the kill lands mid-run on any machine.
KILL_AT=${KILL_AT:-$((ITERATIONS / 3))}
BASE_URL=http://localhost:8080
# Keys outlive the run, so every run gets its own.
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
OUT=build/crash-test/$RUN_ID
DB_USER=${POSTGRES_USER:-tally}

# A throwaway admin key for this run's app only: generated here, passed through the environment, never stored.
TALLY_ADMIN_API_KEY=$(openssl rand -hex 32)
export TALLY_ADMIN_API_KEY

app_pid=""

stop_app() {
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    kill "$app_pid"
    # A JVM stopped by SIGTERM exits with 143, which is the expected end here.
    wait "$app_pid" || true
  fi
}
trap stop_app EXIT

start_app() {
  java -jar "$jar" > "$OUT/app-$1.log" 2>&1 &
  app_pid=$!
  for _ in $(seq 120); do
    if curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
      return
    fi
    sleep 1
  done
  echo "The app did not report UP within 120 s; see $OUT/app-$1.log" >&2
  exit 1
}

create_account() {
  curl -sf -X POST "$BASE_URL/accounts" -H 'Content-Type: application/json' \
    -d "{\"code\":\"crash-$RUN_ID-$1\",\"currency\":\"CAD\",\"allowNegative\":true}" | jq -r .id
}

run_transfers() {
  k6 run --quiet "$@" \
    -e BASE_URL="$BASE_URL" -e RUN_ID="$RUN_ID" -e ACCOUNT_A="$account_a" -e ACCOUNT_B="$account_b" \
    -e ITERATIONS="$ITERATIONS" -e VUS="$VUS" scripts/crash-transfers.js
}

# Read straight from PostgreSQL, so the count does not depend on the app being up. The ids go in as psql
# variables, which psql quotes, rather than being pasted into the SQL.
entries_posted() {
  docker compose exec -T postgres psql -U "$DB_USER" -d tally -tA -v a="$account_a" -v b="$account_b" <<'SQL'
select count(distinct entry_id) from journal_lines where account_id in (:'a', :'b');
SQL
}

# Only the end-of-test summary: after the kill, k6 logs one warning per refused request.
print_summary() {
  sed -n '/TOTAL RESULTS/,$p' "$1"
}

reconcile() {
  # The header comes from stdin, so the key never appears in a process listing.
  curl -sf -X POST "$BASE_URL/admin/reconcile" -H @- <<< "Authorization: Bearer $TALLY_ADMIN_API_KEY"
}

if curl -s -o /dev/null "$BASE_URL/actuator/health"; then
  echo "Something already answers on $BASE_URL; stop it first." >&2
  exit 1
fi

mkdir -p "$OUT"
./gradlew -q bootJar
jar=$(ls build/libs/tally-*.jar | grep -v -- '-plain\.jar$')
docker compose up -d --wait

start_app first
account_a=$(create_account a)
account_b=$(create_account b)
echo "Run $RUN_ID: $ITERATIONS transfers between $account_a and $account_b with $VUS VUs; kill -9 at $KILL_AT posted"

# Thresholds are off for this run only: every request after the kill fails, which is the point.
run_transfers --no-thresholds > "$OUT/k6-first.txt" 2>&1 &
k6_pid=$!
until (( $(entries_posted) >= KILL_AT )); do
  if ! kill -0 "$k6_pid" 2>/dev/null; then
    echo "The first run ended before $KILL_AT transfers were posted; see $OUT" >&2
    exit 1
  fi
  sleep 0.2
done
kill -9 "$app_pid"
# A JVM killed with SIGKILL exits with 137, which is the point of this step.
wait "$app_pid" || true
posted_at_kill=$(entries_posted)
wait "$k6_pid"
echo "Killed the app with kill -9 at $(date -u +%H:%M:%SZ); entries posted then: $posted_at_kill"
print_summary "$OUT/k6-first.txt"

start_app replay
if ! run_transfers > "$OUT/k6-replay.txt" 2>&1; then
  print_summary "$OUT/k6-replay.txt"
  echo "The replay run had failed requests; see $OUT" >&2
  exit 1
fi
print_summary "$OUT/k6-replay.txt"

posted=$(entries_posted)
report=$(reconcile)
drift=$(jq .driftedAccounts <<< "$report")
echo "Entries after the replay: $posted (one per key: $ITERATIONS)"
echo "Reconciliation: $report"
echo "Drift count: $drift"

if [[ "$posted" != "$ITERATIONS" || "$drift" != "0" ]]; then
  echo "Crash test FAILED" >&2
  exit 1
fi
echo "Crash test passed"
