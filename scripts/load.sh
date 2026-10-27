#!/usr/bin/env bash
# Load test, not part of the build. Builds the jar, starts a Docker Compose stack of its own with an empty database,
# starts the app with the quiet `load` profile (load/application-load.yaml) and runs k6 against it. Each run's k6 JSON
# summary and console output go to load/results/<date>/, with the test conditions and samples of the app, pool and
# database taken during it.
#
#   scripts/load.sh              scenario A (load/transfers.js), then scenario B (load/hot-account.js)
#   scripts/load.sh saturation   scenario A at each of STEPS virtual users in turn, STEP_DURATION each
#
# Needs Docker, k6, jq and curl. The stack is the Compose project tally-load, on the ports of the bootRun stack, so
# stop that one first. Its database is emptied at every start, so every run starts from the same state; the stack is
# left running afterwards for a look in Grafana (localhost:3000), and `docker compose -p tally-load down -v` removes it.
set -euo pipefail

cd "$(dirname "$0")/.."

MODE=${1:-scenarios}
# The saturation run's steps, in virtual users; choose them from what the machine can take.
STEPS=${STEPS:-10 25 50 100 200}
STEP_DURATION=${STEP_DURATION:-3m}
# Seconds between samples; taking one adds about 2 s of its own (docker stats).
SAMPLE_INTERVAL=${SAMPLE_INTERVAL:-5}
BASE_URL=http://localhost:8080
DB_USER=${POSTGRES_USER:-tally}
read -r DAY TIME <<< "$(date -u '+%Y-%m-%d %H%M%SZ')"
# Every file of one invocation starts with its time, so several invocations on one day sit side by side.
PREFIX=load/results/$DAY/$TIME
# The app's log is not a result, so it stays under build/, which is never committed.
LOGS=build/load/$DAY-$TIME
# CPU columns are percent of all cores (the JVM's own two readings) or of one core (docker stats).
SAMPLE_COLUMNS=time_utc,app_cpu_pct,host_cpu_pct,pool_active,pool_pending,db_active,db_lock_waits,db_idle_in_transaction,outbox_unpublished,postgres_cpu_pct,kafka_cpu_pct,lgtm_cpu_pct

app_pid=""
# Labels of the runs whose thresholds were crossed. A string, not an array: bash 3.2 (macOS) treats an empty array
# as unset under `set -u`.
crossed=""

compose() {
  docker compose -p tally-load "$@"
}

stop_app() {
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    kill "$app_pid"
    # A JVM stopped by SIGTERM exits with 143, which is the expected end here.
    wait "$app_pid" || true
  fi
}
trap stop_app EXIT

start_app() {
  java -jar "$jar" --spring.config.additional-location=file:load/ --spring.profiles.active=load \
    > "$LOGS/app.log" 2>&1 &
  app_pid=$!
  for _ in $(seq 120); do
    if curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
      return
    fi
    sleep 1
  done
  echo "The app did not report UP within 120 s; see $LOGS/app.log" >&2
  exit 1
}

# One value from /actuator/metrics, which only the load profile exposes, put through the jq filter in $2.
metric() {
  curl -sf "$BASE_URL/actuator/metrics/$1" | jq ".measurements[0].value | $2"
}

machine_facts() {
  if [[ $(uname -s) == Darwin ]]; then
    echo "cpu: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores"
    echo "ram: $(( $(sysctl -n hw.memsize) / 1024 ** 3 )) GiB"
    echo "os: $(sw_vers -productName) $(sw_vers -productVersion)"
  else
    echo "cpu: $(lscpu | sed -n 's/^Model name: *//p'), $(nproc) cores"
    echo "ram: $(( $(awk '/^MemTotal:/ { print $2 }' /proc/meminfo) / 1024 ** 2 )) GiB (rounded down)"
    echo "os: $(uname -sr)"
  fi
}

write_conditions() {
  {
    echo "started: $DAY $TIME"
    echo "mode: $MODE$([[ $MODE == saturation ]] && echo ", steps of $STEPS VUs, $STEP_DURATION each")"
    echo "commit: $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- || echo ', with uncommitted changes')"
    machine_facts
    echo "java: $(java -version 2>&1 | head -1)"
    echo "k6: $(k6 version | head -1)"
    echo "docker: $(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.NCPU}} CPUs and {{.MemTotal}} bytes') for its VM"
    echo "images: $(compose config --images | sort | paste -sd ' ' -)"
    echo "app: the boot jar with the load profile; k6, the app and the stack share this machine"
    # Read from the running app, so it is the size in effect, not the one in a file.
    echo "connection pool: $(metric hikaricp.connections.max floor) connections"
  } > "$PREFIX-conditions.txt"
}

# The database side, read as the owner, who sees every session.
db_sample() {
  compose exec -T postgres psql -U "$DB_USER" -d tally -tA -F, <<'SQL'
select count(*) filter (where state = 'active'),
       count(*) filter (where wait_event_type = 'Lock'),
       count(*) filter (where state = 'idle in transaction'),
       (select count(*) from outbox where published_at is null)
from pg_stat_activity
where usename = 'tally_app';
SQL
}

container_cpu() {
  docker stats --no-stream --format '{{.CPUPerc}}' "$postgres_id" "$kafka_id" "$lgtm_id" | tr -d '%' | paste -sd, -
}

# A reading that fails (the app not answering, say) leaves its cells empty rather than stopping the run.
sample_while_running() {
  echo "$SAMPLE_COLUMNS"
  while kill -0 "$1" 2>/dev/null; do
    echo "$(date -u +%H:%M:%S),$(metric process.cpu.usage '. * 100 | round'),$(metric system.cpu.usage '. * 100 | round'),$(metric hikaricp.connections.active floor),$(metric hikaricp.connections.pending floor),$(db_sample),$(container_cpu)"
    sleep "$SAMPLE_INTERVAL"
  done
}

# k6 exits with 99 when a threshold is crossed: the run still counts and its summary is kept. Any other failure stops
# the script.
run_k6() {
  local label=$1 script=$2
  shift 2
  echo "$(date -u +%H:%M:%SZ) running $label: $script $*"
  k6 run --quiet --summary-export="$PREFIX-$label.json" -e BASE_URL="$BASE_URL" -e RUN_ID="load-$DAY-$TIME-$label" \
    "$@" "$script" > "$PREFIX-$label.txt" 2>&1 &
  local k6_pid=$!
  sample_while_running "$k6_pid" > "$PREFIX-$label-samples.csv"
  local status=0
  wait "$k6_pid" || status=$?
  cat "$PREFIX-$label.txt"
  if [[ $status -eq 99 ]]; then
    crossed="$crossed $label"
  elif [[ $status -ne 0 ]]; then
    echo "k6 failed with exit code $status on $label; see $PREFIX-$label.txt" >&2
    exit 1
  fi
}

# The transfers alone, from the scenario's sub-metrics (load/common.js). k6 divides the count by the whole run, so
# requests per second include the seeding time that load/seed.js prints.
report() {
  echo
  {
    printf 'run\trequests\treq/s\tp50 ms\tp95 ms\tp99 ms\tfailed %%\n'
    for summary in "$PREFIX"-*.json; do
      local run
      run=$(basename "$summary" .json)
      jq -r --arg run "${run#"$TIME"-}" '
        def ms: . * 10 | round / 10;
        .metrics as $m
        | ($m | keys[] | select(startswith("http_reqs{")) | ltrimstr("http_reqs")) as $tag
        | [$run, $m["http_reqs" + $tag].count, ($m["http_reqs" + $tag].rate | round),
           ($m["http_req_duration" + $tag] | (.med | ms), (.["p(95)"] | ms), (.["p(99)"] | ms)),
           ($m["http_req_failed" + $tag].value * 10000 | round / 100)]
        | @tsv' "$summary"
    done
  } | column -t -s $'\t'
}

case $MODE in
  scenarios | saturation) ;;
  *)
    echo "Usage: scripts/load.sh [saturation]" >&2
    exit 2
    ;;
esac

if curl -s -o /dev/null "$BASE_URL/actuator/health"; then
  echo "Something already answers on $BASE_URL; stop it first." >&2
  exit 1
fi

mkdir -p "$(dirname "$PREFIX")" "$LOGS"
./gradlew -q bootJar
jar=$(ls build/libs/tally-*.jar | grep -v -- '-plain\.jar$')
if [[ $(wc -l <<< "$jar") -ne 1 ]]; then
  echo "Expected one boot jar in build/libs, found: $jar. Run ./gradlew clean first." >&2
  exit 1
fi

# Removes the last run's stack with its database, so every run starts from an empty ledger.
compose down -v --remove-orphans
compose up -d --wait
postgres_id=$(compose ps -q postgres)
kafka_id=$(compose ps -q kafka)
lgtm_id=$(compose ps -q lgtm)

start_app
write_conditions
cat "$PREFIX-conditions.txt"

if [[ $MODE == saturation ]]; then
  for vus in $STEPS; do
    printf -v label 'saturation-%03dvus' "$vus"
    run_k6 "$label" load/transfers.js -e VUS="$vus" -e DURATION="$STEP_DURATION"
  done
else
  run_k6 transfers load/transfers.js
  run_k6 hot-account load/hot-account.js
fi

report
if [[ -n "$crossed" ]]; then
  echo "Thresholds crossed in:$crossed" >&2
  exit 1
fi
