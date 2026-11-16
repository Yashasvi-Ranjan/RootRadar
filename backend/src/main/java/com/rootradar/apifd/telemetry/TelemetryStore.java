package com.rootradar.apifd.telemetry;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Write side of telemetry_snapshots / dependency_measurements. Every insert is
 * {@code ON CONFLICT DO NOTHING} on the table's unique window key, which is what makes ingestion idempotent
 * (re-polling overlapping windows is safe, also under concurrent pollers).
 */
@Component
class TelemetryStore {

    record SnapshotRow(long serviceId, Instant collectedAt, Instant windowStart, Instant windowEnd, int windowSeconds,
                       String method, String endpoint, long count, long errors, double avg, double p50, double p95,
                       double p99, double max) {}

    record DependencyRow(long callerId, String name, String type, Instant collectedAt, Instant windowStart,
                         Instant windowEnd, int windowSeconds, long count, long errors, long timeouts, double avg,
                         double p50, double p95, double p99, double max) {}

    private static final String SNAPSHOT_SQL = """
            insert into telemetry_snapshots (service_id, collected_at, window_start, window_end, window_seconds, method,
                endpoint, request_count, error_count, avg_ms, p50_ms, p95_ms, p99_ms, max_ms)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (service_id, endpoint, method, window_start) do nothing
            """;
    private static final String DEPENDENCY_SQL = """
            insert into dependency_measurements (caller_service_id, dependency_name, dependency_type, collected_at,
                window_start, window_end, window_seconds, request_count, error_count, timeout_count, avg_ms, p50_ms,
                p95_ms, p99_ms, max_ms)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (caller_service_id, dependency_name, window_start) do nothing
            """;

    private final JdbcTemplate jdbc;

    TelemetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return per-row insert counts (1 = new, 0 = already stored). */
    int[] insertSnapshots(List<SnapshotRow> rows) {
        if (rows.isEmpty()) return new int[0];
        return jdbc.batchUpdate(SNAPSHOT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                SnapshotRow r = rows.get(i);
                ps.setLong(1, r.serviceId());
                ps.setObject(2, utc(r.collectedAt()));
                ps.setObject(3, utc(r.windowStart()));
                ps.setObject(4, utc(r.windowEnd()));
                ps.setInt(5, r.windowSeconds());
                ps.setString(6, r.method());
                ps.setString(7, r.endpoint());
                ps.setLong(8, r.count());
                ps.setLong(9, r.errors());
                ps.setDouble(10, r.avg());
                ps.setDouble(11, r.p50());
                ps.setDouble(12, r.p95());
                ps.setDouble(13, r.p99());
                ps.setDouble(14, r.max());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    int[] insertDependencies(List<DependencyRow> rows) {
        if (rows.isEmpty()) return new int[0];
        return jdbc.batchUpdate(DEPENDENCY_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                DependencyRow r = rows.get(i);
                ps.setLong(1, r.callerId());
                ps.setString(2, r.name());
                ps.setString(3, r.type());
                ps.setObject(4, utc(r.collectedAt()));
                ps.setObject(5, utc(r.windowStart()));
                ps.setObject(6, utc(r.windowEnd()));
                ps.setInt(7, r.windowSeconds());
                ps.setLong(8, r.count());
                ps.setLong(9, r.errors());
                ps.setLong(10, r.timeouts());
                ps.setDouble(11, r.avg());
                ps.setDouble(12, r.p50());
                ps.setDouble(13, r.p95());
                ps.setDouble(14, r.p99());
                ps.setDouble(15, r.max());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /** Newest stored service-wide window end for the node, used as the {@code since} cursor. */
    Instant latestWindowEnd(long serviceId) {
        OffsetDateTime t = jdbc.queryForObject(
                "select max(window_end) from telemetry_snapshots where service_id = ? and endpoint = '*' and method = '*'",
                OffsetDateTime.class, serviceId);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
