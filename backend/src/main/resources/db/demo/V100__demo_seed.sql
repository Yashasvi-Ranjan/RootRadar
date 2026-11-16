-- V100: demo seed (only loaded with the `demo` profile via spring.flyway.locations). Owned by Agent 1.
-- Registers the 4 demo nodes and the dependency chain  gateway -> order -> payment -> database.
INSERT INTO services (name, display_name, node_type, base_url, health_endpoint, telemetry_endpoint,
                      check_interval_seconds, timeout_ms, enabled, demo, description) VALUES
 ('gateway-service','Gateway / Checkout API','HTTP_SERVICE','http://localhost:8081','/health','/internal/telemetry',10,2000,TRUE,TRUE,'Demo checkout entry point'),
 ('order-service','Order Service','HTTP_SERVICE','http://localhost:8082','/health','/internal/telemetry',10,2000,TRUE,TRUE,'Demo order service'),
 ('payment-service','Payment Service','HTTP_SERVICE','http://localhost:8083','/health','/internal/telemetry',10,2000,TRUE,TRUE,'Demo payment service (uses PostgreSQL)'),
 ('database','PostgreSQL (payments schema)','DEPENDENCY_ONLY',NULL,NULL,NULL,10,2000,TRUE,TRUE,
  'Not an HTTP node: metrics are the JDBC dependency measurements reported by payment-service');

INSERT INTO service_dependencies (from_service_id, to_service_id, measurement_name)
SELECT f.id, t.id, m.mname FROM (VALUES
  ('gateway-service','order-service','order-service'),
  ('order-service','payment-service','payment-service'),
  ('payment-service','database','database')) AS m(fname,tname,mname)
JOIN services f ON f.name = m.fname JOIN services t ON t.name = m.tname;
