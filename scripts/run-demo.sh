#!/usr/bin/env bash
# Guided, fully automatic run of the required demo against a RUNNING stack (scripts/start-all.sh first).
# Nothing is inserted by hand: incidents and diagnoses come from the real detection pipeline.
# Usage: scripts/run-demo.sh [--baseline SECONDS] [--fault-ms MS] [--hold SECONDS]
#   --baseline  healthy traffic before the fault (default 150; the detector needs >= 12 healthy 10-s windows = 120 s)
#   --fault-ms  injected payment-service delay (default 2000)
#   --hold      keep the fault this long after the incident is ACTIVE (default 30)
# Requires: curl, jq. Total runtime is about baseline + 3 minutes.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="${API_URL:-http://localhost:8080}"
BASELINE=150; FAULT_MS=2000; HOLD=30
while [ $# -gt 0 ]; do case "$1" in
  --baseline) BASELINE=$2; shift 2;; --fault-ms) FAULT_MS=$2; shift 2;; --hold) HOLD=$2; shift 2;;
  *) sed -n '2,9p' "$0"; exit 0;; esac; done
command -v jq >/dev/null || { echo "jq is required (brew install jq)"; exit 1; }
step() { printf '\n== Step %s: %s\n' "$1" "$2"; }
now() { date +%s; }
TRAFFIC_PID=""
cleanup() { [ -n "$TRAFFIC_PID" ] && kill "$TRAFFIC_PID" 2>/dev/null; "$ROOT/scripts/fault.sh" clear >/dev/null 2>&1; }
trap cleanup EXIT INT TERM

step 1 "stack is up (backend, 3 demo services, database node)"
curl -fsS "$API/api/system/info" | jq -c . || { echo "backend not reachable; run scripts/start-all.sh"; exit 1; }
curl -fsS "$API/api/services" | jq -r '.[] | "  \(.service.name)\t\(.service.nodeType)\tdemo=\(.service.demo)\thealth=\(.health.status // "n/a")"'
"$ROOT/scripts/fault.sh" clear >/dev/null || { echo "demo services not reachable"; exit 1; }

step 2 "normal traffic for ${BASELINE}s (building the latency baseline)"
"$ROOT/scripts/traffic.sh" -r 5 -q >/dev/null 2>&1 & TRAFFIC_PID=$!
sleep "$BASELINE"

step 3 "healthy: no active incident"
ACTIVE=$(curl -fsS "$API/api/incidents/active" | jq length); echo "  active incidents: $ACTIVE"
curl -fsS "$API/api/services" | jq -r '.[] | select(.metrics) | "  \(.service.name): p95 \(.metrics.p95Ms|floor) ms, error rate \(.metrics.errorRate), \(.metrics.requestCount) requests (last 5 min)"'
[ "$ACTIVE" = 0 ] || echo "  NOTE: an incident from an earlier run is still open; the demo continues."

step 4 "inject ${FAULT_MS} ms delay into payment-service"
T0=$(now); "$ROOT/scripts/fault.sh" payment-delay "$FAULT_MS" | jq -c '{service,latencyMs,active}'

step 5 "wait for detection (anomaly -> dependency analysis -> RCA -> incident)"
ID=""
for _ in $(seq 1 120); do
  ID=$(curl -fsS "$API/api/incidents/active" | jq -r '[.[] | select(.status=="ACTIVE")][0].id // empty')
  [ -n "$ID" ] && break; sleep 3
done
[ -n "$ID" ] || { echo "  no incident after 6 minutes: detection did NOT work"; exit 1; }
echo "  incident #$ID ACTIVE after $(( $(now) - T0 )) s"

step 6 "diagnosis: ranked services with per-rule evidence"
curl -fsS "$API/api/incidents/$ID/diagnosis" | jq -r '.summary, (.findings[] | "  #\(.rank) \(.serviceName)  \(.classification)  score \(.score)/100", (.factors[] | select(.fired) | "       \(.points|tostring|if .[0:1]=="-" then . else "+"+. end)  \(.label)"))'

step 7 "incident timeline"
curl -fsS "$API/api/incidents/$ID/timeline" | jq -r '.[] | select(.eventType!="ANOMALY_DETECTED") | "  \(.occurredAt)  \(.eventType)  \(.title)"'
echo "  Open the UI: http://localhost:5173/incidents/$ID"
sleep "$HOLD"

step 8 "clear the fault (recovery)"
T1=$(now); "$ROOT/scripts/fault.sh" clear >/dev/null && echo "  all injected faults cleared"

step 9 "wait for recovery -> RESOLVED"
for _ in $(seq 1 120); do
  ST=$(curl -fsS "$API/api/incidents/$ID" | jq -r '.incident.status'); [ "$ST" = RESOLVED ] && break; sleep 3
done
echo "  incident #$ID is $ST after $(( $(now) - T1 )) s"

step 10 "history is still available"
curl -fsS "$API/api/incidents?size=5" | jq -r '.items[] | "  #\(.id) \(.status) \(.severity) \(.title)  root cause: \(.rootCause.name) (\(.rootCauseScore))  resolved: \(.resolvedAt)"'
curl -fsS "$API/api/incidents/$ID" | jq -r '"  anomalies recorded: \(.anomalies|length), timeline entries: \(.timeline|length)"'
kill "$TRAFFIC_PID" 2>/dev/null; TRAFFIC_PID=""
echo; echo "Demo finished. Steps 11-12 are in the UI: http://localhost:5173 (Incidents -> #$ID, Services -> payment-service charts)."
