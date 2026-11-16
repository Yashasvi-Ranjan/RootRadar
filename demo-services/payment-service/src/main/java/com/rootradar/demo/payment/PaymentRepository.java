package com.rootradar.demo.payment;

import com.rootradar.demo.common.FaultService;
import com.rootradar.demo.common.TelemetryRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL against schema demo_payments. Every operation is measured as dependency "database" (type JDBC). When
 * dbLatencyMs is injected, a real {@code SELECT pg_sleep(..)} runs inside the measured call so the database
 * dependency is genuinely slow.
 */
@Repository
public class PaymentRepository {
    private static final String DB = "database";
    private final JdbcTemplate jdbc;
    private final TelemetryRegistry telemetry;
    private final FaultService faults;

    public PaymentRepository(JdbcTemplate jdbc, TelemetryRegistry telemetry, FaultService faults) {
        this.jdbc = jdbc;
        this.telemetry = telemetry;
        this.faults = faults;
    }

    public long insert(String orderId, BigDecimal amount) {
        return measured(() -> {
            sleepIfInjected();
            Long id = jdbc.queryForObject(
                    "INSERT INTO demo_payments.payments(order_id, amount, status) VALUES (?, ?, 'APPROVED') RETURNING id",
                    Long.class, orderId, amount);
            return id;
        });
    }

    public Map<String, Object> find(long id) {
        return measured(() -> {
            sleepIfInjected();
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT id, order_id, amount, status, created_at FROM demo_payments.payments WHERE id = ?",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("paymentId", rs.getLong("id"));
                        m.put("orderId", rs.getString("order_id"));
                        m.put("amount", rs.getBigDecimal("amount"));
                        m.put("status", rs.getString("status"));
                        Timestamp ts = rs.getTimestamp("created_at");
                        m.put("createdAt", ts.toInstant().toString());
                        return m;
                    }, id);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    private void sleepIfInjected() {
        int ms = faults.dbLatencyMs();
        if (ms > 0) {
            jdbc.query("SELECT pg_sleep(?)", rs -> {
            }, ms / 1000.0);
        }
    }

    private <T> T measured(java.util.function.Supplier<T> op) {
        long t0 = System.nanoTime();
        boolean error = false;
        try {
            return op.get();
        } catch (RuntimeException e) {
            error = true;
            throw e;
        } finally {
            telemetry.recordDependency(DB, "JDBC", (System.nanoTime() - t0) / 1e6, error, false);
        }
    }
}
