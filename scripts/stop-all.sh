#!/usr/bin/env bash
# Stop everything started by start-all.sh. Only PIDs recorded in .run/*.pid are touched, and only if the process
# command line still looks like ours (java -jar ...-SNAPSHOT.jar / vite), so a recycled PID is never killed.
# Idempotent: safe to run when nothing is running. Does NOT stop PostgreSQL.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.run"
[ -d "$RUN" ] || { echo "Nothing to stop (no .run directory)."; exit 0; }
for name in frontend backend gateway order payment; do
  f="$RUN/$name.pid"; [ -f "$f" ] || continue
  pid=$(cat "$f")
  if ! kill -0 "$pid" 2>/dev/null; then echo "$name: not running (stale pid file)"; rm -f "$f"; continue; fi
  cmd=$(ps -p "$pid" -o command= 2>/dev/null)
  case "$cmd" in
    *-SNAPSHOT.jar*|*vite*) ;;
    *) echo "$name: pid $pid is not ours ($cmd); leaving it alone"; rm -f "$f"; continue ;;
  esac
  kill "$pid" 2>/dev/null
  for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$pid" 2>/dev/null; then echo "$name: still alive after 10 s, sending SIGKILL"; kill -9 "$pid" 2>/dev/null; fi
  echo "$name: stopped (pid $pid)"; rm -f "$f"
done
