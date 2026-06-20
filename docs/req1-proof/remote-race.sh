#!/usr/bin/env bash
#
# Remote Req-1 proof: fire N concurrent POST /orders at the LIVE server against a
# product with stock = 1, then show how many succeeded and the final stock.
#
# This proves the WITH-lock invariant end-to-end: across Nginx, BOTH app
# instances (two JVMs), and the shared Postgres row. Exactly one order must win.
#
# (The oversell/"without-lock" case is NOT reachable over HTTP by design — the
#  naive checkout service has no controller. That stays a local JUnit test.)
#
# Requires: curl, jq
# Usage:    ./remote-race.sh              # hits https://shopcore.duckdns.org
#           BASE=http://localhost:8005 ./remote-race.sh   # hit local stack
set -euo pipefail

BASE="${BASE:-https://shopcore.duckdns.org}"
THREADS="${THREADS:-50}"
STAMP="$(date +%s)"

echo "== Target: $BASE  |  concurrency: $THREADS =="

# 1) Register a fresh user
USER_ID=$(curl -fsS -X POST "$BASE/users/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"racer_$STAMP\",\"password\":\"pass1234\"}" | jq -r '.id')
echo "user id        = $USER_ID"

# 2) Create a fresh product with stock = 1
PRODUCT_ID=$(curl -fsS -X POST "$BASE/products" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"RACE_$STAMP\",\"price\":9.99,\"stockQuantity\":1}" | jq -r '.id')
echo "product id     = $PRODUCT_ID (stock = 1)"

# 3) Fire THREADS concurrent orders, collect only the HTTP status codes
BODY="{\"userId\":$USER_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}"
echo "firing $THREADS concurrent orders..."

CODES=$(seq 1 "$THREADS" | xargs -P "$THREADS" -I{} \
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST "$BASE/orders" -H 'Content-Type: application/json' -d "$BODY")

SUCCESS=$(echo "$CODES" | grep -c '^201$' || true)
CONFLICT=$(echo "$CODES" | grep -c '^409$' || true)
BADREQ=$(echo "$CODES"  | grep -c '^400$' || true)
OTHER=$(echo "$CODES"   | grep -vcE '^(201|409|400)$' || true)

# 4) Read final stock back
FINAL_STOCK=$(curl -fsS "$BASE/products" \
  | jq -r --arg id "$PRODUCT_ID" '.[] | select(.id == ($id|tonumber)) | .stockQuantity')

echo
echo "================ RESULT ================"
echo " Concurrent orders : $THREADS"
echo " 201 Created (won) : $SUCCESS"
echo " 409 Conflict      : $CONFLICT"
echo " 400 No stock      : $BADREQ"
echo " other             : $OTHER"
echo " final stock       : $FINAL_STOCK"
if [ "$SUCCESS" -eq 1 ] && [ "$FINAL_STOCK" -eq 0 ]; then
  echo " VERDICT           : PASS — exactly one winner, no oversell"
else
  echo " VERDICT           : CHECK — expected 1 winner / stock 0"
fi
echo "======================================="
