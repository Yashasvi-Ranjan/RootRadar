#!/usr/bin/env bash
# Validate the local environment for RootRadar. Exit 0 = ready, 1 = something required is missing.
# Usage: scripts/check-env.sh [--no-ports]    (--no-ports skips the "ports are free" check, used by start-all.sh
#                                              when it manages already-running components itself)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
CHECK_PORTS=1; [ "${1:-}" = "--no-ports" ] && CHECK_PORTS=0
fail=0
ok()   { printf '  [ ok ] %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; fail=1; }
warn() { printf '  [warn] %s\n' "$*"; }

echo "Tools"
if command -v java >/dev/null 2>&1; then
  jv=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[."].*/\1/')
  if [ -n "$jv" ] && [ "$jv" -ge 25 ] 2>/dev/null; then ok "java $jv (>= 25 required)"; else bad "java >= 25 required, found: $(java -version 2>&1 | head -1)"; fi
else bad "java not found (install JDK 25+)"; fi
if command -v mvn >/dev/null 2>&1; then ok "mvn $(mvn -v 2>/dev/null | head -1 | awk '{print $3}')"; else bad "mvn not found (brew install maven)"; fi
if command -v node >/dev/null 2>&1; then
  nv=$(node -v | sed -E 's/v([0-9]+).*/\1/')
  if [ "$nv" -ge 20 ] 2>/dev/null; then ok "node $(node -v) (>= 20 required)"; else bad "node >= 20 required, found $(node -v)"; fi
else bad "node not found (install Node 20+)"; fi
command -v npm  >/dev/null 2>&1 && ok "npm $(npm -v)" || bad "npm not found"
command -v curl >/dev/null 2>&1 && ok "curl" || bad "curl not found"
command -v lsof >/dev/null 2>&1 && ok "lsof" || warn "lsof not found (port checks are skipped)"

echo "PostgreSQL"
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/apifd}"
hostport=$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:postgresql://([^/]+)/.*#\1#')
dbhost="${hostport%%:*}"; dbport="${hostport##*:}"; [ "$dbport" = "$hostport" ] && dbport=5432
dbname=$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:postgresql://[^/]+/([^?]+).*#\1#')
if (exec 3<>"/dev/tcp/$dbhost/$dbport") 2>/dev/null; then
  ok "PostgreSQL reachable at $dbhost:$dbport"
  if command -v psql >/dev/null 2>&1; then
    if PGPASSWORD="${DB_PASSWORD:-apifd_dev}" psql -h "$dbhost" -p "$dbport" -U "${DB_USER:-apifd}" -d "$dbname" -Atc 'select 1' >/dev/null 2>&1; then
      ok "can log in as ${DB_USER:-apifd} to database $dbname"
    else
      bad "cannot log in as ${DB_USER:-apifd} to database '$dbname' (see README: Database setup)"
    fi
  else warn "psql not installed; login not verified"; fi
else bad "PostgreSQL not reachable at $dbhost:$dbport (start it: 'docker compose up -d postgres' or 'brew services start postgresql@15')"; fi

if [ "$CHECK_PORTS" = 1 ] && command -v lsof >/dev/null 2>&1; then
  echo "Ports (must be free before start-all.sh)"
  for p in 8080 8081 8082 8083 5173; do
    if lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1; then bad "port $p is in use: $(lsof -nP -iTCP:$p -sTCP:LISTEN | awk 'NR==2{print $1" pid "$2}')"; else ok "port $p free"; fi
  done
fi
echo
if [ $fail = 0 ]; then echo "Environment OK."; else echo "Environment NOT ready."; fi
exit $fail
