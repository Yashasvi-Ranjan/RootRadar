#!/usr/bin/env bash
# Start the whole demo stack: 3 demo services, the backend and the frontend dev server.
# PostgreSQL must already be running (docker compose up -d postgres, or Homebrew).
# Idempotent: components that are already running (tracked in .run/*.pid) are left alone.
# Usage: scripts/start-all.sh [--build]     --build forces `mvn -DskipTests package` (default: only if a jar is missing)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
RUN="$ROOT/.run"; LOGS="$RUN/logs"; mkdir -p "$LOGS"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-demo}"
FORCE_BUILD=0; [ "${1:-}" = "--build" ] && FORCE_BUILD=1

"$ROOT/scripts/check-env.sh" --no-ports || { echo "Fix the problems above first."; exit 1; }

JARS="backend/target/apifd-backend-0.1.0-SNAPSHOT.jar
demo-services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar
demo-services/order-service/target/order-service-0.1.0-SNAPSHOT.jar
demo-services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar"
need_build=$FORCE_BUILD
for j in $JARS; do [ -f "$j" ] || need_build=1; done
if [ $need_build = 1 ]; then
  echo "Building (mvn -DskipTests package) ..."
  mvn -q -DskipTests package > "$LOGS/build.log" 2>&1 || { echo "Build failed, see $LOGS/build.log"; tail -20 "$LOGS/build.log"; exit 1; }
fi
if [ ! -d frontend/node_modules ]; then
  echo "Installing frontend dependencies (npm install) ..."
  (cd frontend && npm install --no-audit --no-fund > "$LOGS/npm-install.log" 2>&1) || { echo "npm install failed, see $LOGS/npm-install.log"; exit 1; }
fi

is_alive() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }
port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# start <name> <port> <health-url> <command...>   (command runs in the repo root, or in frontend/ for 'frontend')
start() {
  local name=$1 port=$2 url=$3; shift 3
  if is_alive "$name"; then echo "  $name already running (pid $(cat "$RUN/$name.pid"))"; return 0; fi
  rm -f "$RUN/$name.pid"
  if port_busy "$port"; then echo "  ERROR: port $port is used by another process; not starting $name."; return 1; fi
  if [ "$name" = frontend ]; then
    ( cd frontend || exit 1; nohup "$@" > "$LOGS/$name.log" 2>&1 & echo $! > "$RUN/$name.pid" )
  else
    nohup "$@" > "$LOGS/$name.log" 2>&1 &
    echo $! > "$RUN/$name.pid"
  fi
  echo "  started $name (pid $(cat "$RUN/$name.pid"), log .run/logs/$name.log)"
}

wait_ready() {  # <name> <url> <timeout-s>
  local name=$1 url=$2 t=$3 i=0
  while [ $i -lt "$t" ]; do
    if ! is_alive "$name"; then echo "  ERROR: $name exited during startup. Last log lines:"; tail -15 "$LOGS/$name.log"; return 1; fi
    if curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null; then echo "  $name healthy ($url)"; return 0; fi
    sleep 1; i=$((i+1))
  done
  echo "  ERROR: $name not healthy after ${t}s ($url). See $LOGS/$name.log"; return 1
}

echo "Starting components (profile: $SPRING_PROFILES_ACTIVE)"
# Demo services first (payment creates its DB schema), then the backend (polls them), then the UI.
start payment  8083 x java -jar demo-services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar || exit 1
start order    8082 x java -jar demo-services/order-service/target/order-service-0.1.0-SNAPSHOT.jar     || exit 1
start gateway  8081 x java -jar demo-services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar || exit 1
start backend  8080 x java -jar backend/target/apifd-backend-0.1.0-SNAPSHOT.jar                         || exit 1
start frontend 5173 x node node_modules/vite/bin/vite.js --host 127.0.0.1 --strictPort                    || exit 1

echo "Waiting for health ..."
rc=0
wait_ready payment  http://127.0.0.1:8083/health 90 || rc=1
wait_ready order    http://127.0.0.1:8082/health 90 || rc=1
wait_ready gateway  http://127.0.0.1:8081/health 90 || rc=1
wait_ready backend  http://127.0.0.1:8080/actuator/health 120 || rc=1
wait_ready frontend http://127.0.0.1:5173/ 60 || rc=1
if [ $rc != 0 ]; then echo "Some components failed. Run scripts/stop-all.sh to clean up."; exit 1; fi
cat <<MSG

RootRadar is up:
  UI        http://localhost:5173
  Backend   http://localhost:8080   (/api/system/info, /api/stream)
  Demo      gateway :8081  order :8082  payment :8083   (admin token: ${DEMO_ADMIN_TOKEN:-demo-admin-token})
  Logs      .run/logs/*.log        Stop: scripts/stop-all.sh        Demo: scripts/run-demo.sh
MSG
