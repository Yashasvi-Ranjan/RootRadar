# RootRadar - Automatic API Failure Diagnosis System

RootRadar monitors a small microservice chain (`gateway-service -> order-service -> payment-service -> database`),
collects real health checks and request telemetry, detects latency / error-rate anomalies with rolling baselines,
ranks the probable root cause with an explainable rule-based score, and tracks incidents through
`DETECTED -> ACTIVE -> RECOVERING -> RESOLVED`. Everything is persisted in PostgreSQL; the React dashboard updates live over SSE.

- Backend: Java 25 (`maven.compiler.release=25` in the root `pom.xml`), Spring Boot 3.5, Spring Data JPA, Flyway, PostgreSQL. Detection and RCA are pure Java (no external AI).
- Demo services: three Spring Boot apps with real HTTP/JDBC calls and a fault-injection admin API (profile `demo`, localhost only).
- Frontend: React + Vite (JavaScript), React Router, Recharts.

Ports: backend 8080, gateway 8081, order 8082, payment 8083, frontend 5173, PostgreSQL 5432.
More detail: `docs/ARCHITECTURE.md`, `docs/API_CONTRACTS.md`, `docs/ANOMALY_DETECTION.md`, `docs/RCA_SCORING_MODEL.md`,
`docs/DATABASE_DESIGN.md`, `docs/DEMO_GUIDE.md`, and the per-agent reports in `docs/agent-reports/`.

## 1. Prerequisites (fresh machine)
| Tool | Version | macOS install |
|---|---|---|
| JDK | 25 or newer (root `pom.xml` uses `--release 25`) | `brew install openjdk` (JDK 27 was used in development) |
| Maven | 3.9+ | `brew install maven` |
| Node + npm | Node 20+ | `brew install node` |
| PostgreSQL | 15 (any recent) | `brew install postgresql@15 && brew services start postgresql@15`, or Docker (below) |
| curl, lsof | any | preinstalled on macOS |
| jq | any | `brew install jq` (only for `scripts/run-demo.sh`) |

Verify everything with `scripts/check-env.sh` (checks tool versions, PostgreSQL login and that ports 8080-8083 and 5173 are free).

## 2. Database setup (once)
Option A - Homebrew/system PostgreSQL:
```bash
psql postgres -c "create role apifd login password 'apifd_dev'"
psql postgres -c "create database apifd owner apifd"
psql postgres -c "create database apifd_test owner apifd"    # backend integration tests
```
Option B - Docker (creates both databases):
```bash
docker compose up -d postgres
```
Optional: `cp .env.example .env` to change credentials/URLs (all variables are documented there; the scripts load `.env`).
Schema and the demo seed (4 nodes, 3 edges) are created by Flyway when the backend starts (profile `demo`); the demo
payment-service creates its own schema `demo_payments` in the same database.

## 3. Build
```bash
mvn -DskipTests package          # backend + 3 demo services (jars in */target)
cd frontend && npm install       # frontend dependencies
```

## 4. Start / stop everything
```bash
scripts/start-all.sh             # builds missing jars, installs frontend deps if needed, starts 5 processes, waits for health
scripts/stop-all.sh              # stops exactly those processes (PIDs in .run/, logs in .run/logs/)
```
`start-all.sh` is idempotent (already-running components are skipped) and refuses to start a component whose port is used by a
foreign process. Use `scripts/start-all.sh --build` to force a rebuild. PostgreSQL is not started or stopped by the scripts.
Open http://localhost:5173. Manual start without scripts: see `docs/DEMO_GUIDE.md` and run
`SPRING_PROFILES_ACTIVE=demo java -jar backend/target/apifd-backend-0.1.0-SNAPSHOT.jar` (and the three demo jars).

## 5. The demo (12 steps)
Automatic version of steps 1-10 against a running stack (about 5-6 minutes): `scripts/run-demo.sh`.
By hand, in a second terminal:

1. `scripts/start-all.sh`; open http://localhost:5173 (Overview shows 4 nodes labelled DEMO, health UP).
2. `scripts/traffic.sh -r 5` - steady checkout traffic (leave it running; Ctrl-C at the end).
3. Wait about 2.5 minutes. The detector needs >= 12 healthy 10-second windows as baseline. Overview: no incident, latency in milliseconds.
4. `scripts/fault.sh payment-delay 2000` - inject a 2 s delay inside payment-service (its `database` dependency stays ~1 ms).
5. Watch Overview/Incidents: an incident appears about 30-45 s later (measured: 33 s).
6. Open the incident: root cause `payment-service`, `PROBABLE_CAUSE`, score 90/100 with the per-rule breakdown; order and gateway are `AFFECTED`; database normal.
7. Incident timeline shows the anomalies, confirmation and diagnosis events; Dependency graph highlights payment-service.
8. Service detail -> payment-service: p95 latency chart jumps to ~2 s, health checks stay UP (a passing health check is not healthy performance).
9. `scripts/fault.sh clear` - remove the delay.
10. About 30 s later the incident turns RECOVERING, about 20 s after that RESOLVED (measured 33 s / 54 s).
11. Incidents -> filter RESOLVED: history, evidence and timeline remain available (everything is in PostgreSQL).
12. Optional variants: `scripts/fault.sh db-delay 1800` (root cause becomes `database`), `payment-errors 0.5`, `payment-down`; `scripts/fault.sh status`.

Fault injection exists only under the `demo` profile, binds 127.0.0.1 and needs the `X-Admin-Token` header (`DEMO_ADMIN_TOKEN`).

## 6. Tests
```bash
mvn test                                    # everything (about 2 minutes; the order/payment demo tests wait for real 10 s windows)
mvn -pl backend test                        # backend only (uses the real PostgreSQL database apifd_test; no H2/mocks of the DB)
cd frontend && npm test && npm run lint     # Vitest + ESLint
```
Backend integration tests need the `apifd_test` database (section 2). Background schedulers are disabled in tests via
`apifd.monitoring.scheduling-enabled=false` / `apifd.telemetry.scheduling-enabled=false` / `apifd.detection.enabled=false`.

## 7. Configuration
All variables are listed in `.env.example`. Detection/monitoring tuning is in `backend/src/main/resources/application.yml`
(`apifd.*`) and, for thresholds, the `detection_rules` table (editable through the UI "Rules" page or `/api/detection-rules`).

## 8. Troubleshooting
| Symptom | Cause / fix |
|---|---|
| `check-env.sh`: cannot log in as apifd | role/database missing: run the commands in section 2; check `DB_USER`/`DB_PASSWORD` |
| Backend log: `Flyway ... Migration checksum mismatch` or `Detected failed migration` | database was used by an older schema: `psql postgres -c "drop database apifd"` then recreate (section 2) |
| `port 808x is in use` | another process owns it: `lsof -nP -iTCP:8080 -sTCP:LISTEN`; stop it or `scripts/stop-all.sh` |
| UI shows "Cannot reach the backend" / disconnected | backend not up yet or crashed: `tail -50 .run/logs/backend.log` |
| Services listed but health DOWN | demo services not running or URL not in `ALLOWED_HOSTS` (SSRF allowlist, default localhost,127.0.0.1) |
| No incident after injecting a fault | no traffic (`scripts/traffic.sh` must be running: latency is measured from real requests), or baseline too short (< 2 min of traffic) |
| Fault commands return 401 / 404 | wrong `DEMO_ADMIN_TOKEN`, or demo services not started with `SPRING_PROFILES_ACTIVE=demo` (the scripts set it) |
| Maven `release 25` error ("invalid target release") | JDK older than 25 |
| Browser CORS error | the UI must be served from `FRONTEND_ORIGIN` (default http://localhost:5173); use the Vite dev server so `/api` is proxied |
| Reset all data | `scripts/stop-all.sh; psql postgres -c "drop database apifd" -c "create database apifd owner apifd"; scripts/start-all.sh` |

Never commit `.env`; the default credentials are for local development only.
