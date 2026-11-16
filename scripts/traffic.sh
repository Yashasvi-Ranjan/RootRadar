#!/usr/bin/env bash
# Continuous checkout traffic generator (curl based) for the RootRadar demo.
# Usage: scripts/traffic.sh [-r REQ_PER_SEC] [-d DURATION_SECONDS] [-u GATEWAY_URL] [-q]
#   -r  requests per second (default 5)        -d  duration in seconds, 0 = until Ctrl-C (default 0)
#   -u  gateway base URL (default http://localhost:8081)   -q  quiet: only print the summary
# Env equivalents: RATE, DURATION, GATEWAY_URL.
set -u
RATE="${RATE:-5}"; DURATION="${DURATION:-0}"; GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"; QUIET=0
while getopts "r:d:u:qh" opt; do
  case "$opt" in
    r) RATE="$OPTARG" ;; d) DURATION="$OPTARG" ;; u) GATEWAY_URL="$OPTARG" ;; q) QUIET=1 ;;
    *) sed -n '2,6p' "$0"; exit 0 ;;
  esac
done
case "$RATE" in ''|*[!0-9]*|0) echo "RATE must be a positive integer" >&2; exit 2 ;; esac
case "$DURATION" in ''|*[!0-9]*) echo "DURATION must be a non-negative integer" >&2; exit 2 ;; esac

TMP="$(mktemp -d)"; trap 'finish' INT TERM EXIT
FINISHED=0
finish() {
  [ "$FINISHED" = 1 ] && return; FINISHED=1; wait 2>/dev/null
  total=$(cat "$TMP"/res.* 2>/dev/null | wc -l | tr -d ' ')
  ok=$(cat "$TMP"/res.* 2>/dev/null | grep -c '^2' ); [ -z "$ok" ] && ok=0
  echo "sent=$total  2xx=$ok  non-2xx=$((total - ok))"
  rm -rf "$TMP"; exit 0
}

BODY='{"customerId":"c-%d","items":[{"sku":"A-1","quantity":1}],"amount":19.99}'
INTERVAL=$(awk "BEGIN{printf \"%.4f\", 1/$RATE}")
START=$(date +%s); N=0
echo "Sending ~$RATE req/s to $GATEWAY_URL/api/checkout ($([ "$DURATION" = 0 ] && echo 'until Ctrl-C' || echo "${DURATION}s"))"
while :; do
  NOW=$(date +%s)
  [ "$DURATION" != 0 ] && [ $((NOW - START)) -ge "$DURATION" ] && break
  N=$((N + 1))
  (
    out=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 15 -X POST "$GATEWAY_URL/api/checkout" \
          -H 'Content-Type: application/json' -d "$(printf "$BODY" "$N")")
    echo "$out" >> "$TMP/res.$$.$N"
    [ "$QUIET" = 0 ] && echo "$(date +%H:%M:%S) $out"
  ) &
  # bound concurrent in-flight curls
  while [ "$(jobs -rp | wc -l)" -ge 200 ]; do sleep 0.05; done
  sleep "$INTERVAL"
done
