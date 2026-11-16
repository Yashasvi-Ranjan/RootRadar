#!/usr/bin/env bash
# Inject / clear faults on the demo services (profile demo only; admin API on localhost).
# Usage: scripts/fault.sh <command> [args]
#   payment-delay <ms> [jitterMs]   slow payment-service business endpoints (service latency, DB stays fast)
#   db-delay <ms>                   slow the payment DB queries (pg_sleep inside the measured JDBC call)
#   payment-errors <rate 0..1> [status]   fraction of payment requests answered with HTTP 5xx (default 500)
#   payment-down                    payment-service answers 503 (add "health" to also fail /health)
#   order-delay <ms> | gateway-delay <ms>
#   clear [service]                 clear faults (default: all three services)
#   status                          show the fault state of all services
# Env: DEMO_ADMIN_TOKEN (default demo-admin-token). Optional trailing "for=<seconds>" auto-clears the fault.
set -eu
TOKEN="${DEMO_ADMIN_TOKEN:-demo-admin-token}"
port() { case "$1" in gateway) echo 8081;; order) echo 8082;; payment) echo 8083;; *) echo "unknown service $1" >&2; exit 2;; esac; }
put() { # service json
  curl -fsS -X PUT "http://localhost:$(port "$1")/admin/faults" -H "X-Admin-Token: $TOKEN" \
       -H 'Content-Type: application/json' -d "$2"; echo; }
DUR=""; ARGS=()
for a in "$@"; do case "$a" in for=*) DUR=",\"durationSeconds\":${a#for=}";; *) ARGS+=("$a");; esac; done
set -- ${ARGS[@]+"${ARGS[@]}"}
cmd="${1:-}"; shift || true
case "$cmd" in
  payment-delay)  put payment "{\"latencyMs\":${1:?ms required},\"latencyJitterMs\":${2:-0}$DUR}" ;;
  order-delay)    put order   "{\"latencyMs\":${1:?ms required}$DUR}" ;;
  gateway-delay)  put gateway "{\"latencyMs\":${1:?ms required}$DUR}" ;;
  db-delay)       put payment "{\"dbLatencyMs\":${1:?ms required}$DUR}" ;;
  payment-errors) put payment "{\"errorRate\":${1:?rate required},\"errorStatus\":${2:-500}$DUR}" ;;
  payment-down)   put payment "{\"unavailable\":true,\"affectsHealth\":$([ "${1:-}" = health ] && echo true || echo false)$DUR}" ;;
  clear)
    for s in ${1:-gateway order payment}; do
      echo -n "$s: "; curl -fsS -X DELETE "http://localhost:$(port "$s")/admin/faults" -H "X-Admin-Token: $TOKEN"; echo
    done ;;
  status)
    for s in gateway order payment; do
      echo -n "$s: "; curl -fsS "http://localhost:$(port "$s")/admin/faults" -H "X-Admin-Token: $TOKEN"; echo
    done ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
