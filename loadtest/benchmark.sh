#!/usr/bin/env bash
#
# Req 10 — automated BEFORE/AFTER caching benchmark.
#
# Runs the k6 stress profile (stress.js) against a SINGLE app instance twice:
#   1. BEFORE — Redis cache DISABLED  (spring.cache.type=none, @Cacheable is a no-op)
#   2. AFTER  — Redis cache ENABLED   (spring.cache.type=redis)
# then prints the before/after table straight from Hibernate's own DB-load
# counters (/admin/db-stats) plus k6 latency/throughput.
#
# WHY A SINGLE INSTANCE: Hibernate statistics are per-JVM, so measuring one
# instance captures ALL the DB load in one place. Two instances behind Nginx
# would split the counter and muddy the result. The cache toggle is the only
# variable that changes between the two runs — everything else is identical.
#
# This is the reproducible twin of the committed report in docs/benchmark/.
#
# REQUIREMENTS: docker (compose v2), curl, python3 (optional, for the table).
# Run it during LOW traffic: it puts PEAK_VUS load on the instance and restarts
# that instance twice. It ALWAYS restores the cache-enabled state on exit.
#
# USAGE:
#   ./loadtest/benchmark.sh
#   PEAK_VUS=200 ./loadtest/benchmark.sh
#   INSTANCE_URL=http://localhost:8006 APP_SERVICE=app1 ./loadtest/benchmark.sh
#
set -euo pipefail

INSTANCE_URL="${INSTANCE_URL:-http://localhost:8006}"   # app1, direct (bypasses Nginx)
APP_SERVICE="${APP_SERVICE:-app1}"
PEAK_VUS="${PEAK_VUS:-120}"
OUT="${OUT:-/tmp/shopcore-bench}"
COMPOSE=(docker compose --profile prod)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$OUT"

echo "== ShopCore before/after caching benchmark =="
echo "   instance : $INSTANCE_URL ($APP_SERVICE)"
echo "   peak VUs : $PEAK_VUS"
echo "   output   : $OUT"
echo

# Always put the cache back on when we're done (success, failure, or Ctrl-C).
restore() {
  echo
  echo "↩  restoring $APP_SERVICE with cache ENABLED (spring.cache.type=redis)..."
  SPRING_CACHE_TYPE=redis "${COMPOSE[@]}" up -d --no-deps "$APP_SERVICE" >/dev/null 2>&1 || true
}
trap restore EXIT

wait_up() {
  for _ in $(seq 1 90); do
    curl -sf "$INSTANCE_URL/admin/db-stats" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "!! instance never became healthy at $INSTANCE_URL" >&2
  exit 1
}

k6_run() { # extra args... ; always runs stress.js
  docker run --rm --network host \
    -e BASE_URL="$INSTANCE_URL" -e PEAK_VUS="$PEAK_VUS" \
    -v "$SCRIPT_DIR:/ld:ro" grafana/k6 run "$@" /ld/stress.js
}

run_phase() { # <label> <cache_type>
  local label="$1" ctype="$2"
  echo "=== Phase: $label  (spring.cache.type=$ctype) ==="
  echo "   recreating $APP_SERVICE ..."
  SPRING_CACHE_TYPE="$ctype" "${COMPOSE[@]}" up -d --no-deps --force-recreate "$APP_SERVICE" >/dev/null
  wait_up
  echo "   warm-up run (discarded) ..."
  k6_run >/dev/null 2>&1 || true
  curl -s -X POST "$INSTANCE_URL/admin/db-stats/reset" >/dev/null
  echo "   measured run ..."
  k6_run --summary-export="$OUT/$label-k6.json" >"$OUT/$label-k6.txt" 2>&1 || true
  curl -s "$INSTANCE_URL/admin/db-stats" >"$OUT/$label-dbstats.json"
  echo "   done -> $OUT/$label-*.{txt,json}"
  echo
}

run_phase before none
run_phase after  redis

echo "================= BEFORE / AFTER ================="
if command -v python3 >/dev/null 2>&1; then
  python3 - "$OUT" <<'PY'
import json, sys, os
out = sys.argv[1]
def load(p):
    try:
        with open(p) as f: return json.load(f)
    except Exception: return {}
db_b, db_a = load(f"{out}/before-dbstats.json"), load(f"{out}/after-dbstats.json")
k6_b, k6_a = load(f"{out}/before-k6.json").get("metrics", {}), load(f"{out}/after-k6.json").get("metrics", {})

def pct(b, a):
    try: return f"{(a-b)/b*100:+.1f}%" if b else "n/a"
    except Exception: return "n/a"
def line(name, b, a, fmt="{:,.0f}"):
    try: bs, as_ = fmt.format(b), fmt.format(a)
    except Exception: bs, as_ = str(b), str(a)
    print(f"{name:30} {bs:>14} {as_:>14}   {pct(b,a):>9}")

print(f"{'metric':30} {'BEFORE (no cache)':>14} {'AFTER (cached)':>14}   {'change':>9}")
print("-"*72)
for key, label in [("prepareStatementCount","JDBC statements"),
                   ("queryExecutionCount","query executions"),
                   ("entityLoadCount","entity loads")]:
    line(label, db_b.get(key,0), db_a.get(key,0))
def m(metrics, name, field): return (metrics.get(name) or {}).get(field, 0)
line("throughput req/s", m(k6_b,"http_reqs","rate"), m(k6_a,"http_reqs","rate"), "{:,.1f}")
line("latency avg (ms)", m(k6_b,"http_req_duration","avg"), m(k6_a,"http_req_duration","avg"), "{:,.2f}")
line("browse p95 (ms)", m(k6_b,"http_req_duration{kind:browse}","p(95)"),
                         m(k6_a,"http_req_duration{kind:browse}","p(95)"), "{:,.2f}")
PY
else
  echo "(python3 not found — raw db-stats below)"
  echo "BEFORE:"; cat "$OUT/before-dbstats.json"; echo
  echo "AFTER :"; cat "$OUT/after-dbstats.json"; echo
fi
echo "=================================================="
echo "Raw k6 output: $OUT/before-k6.txt , $OUT/after-k6.txt"
