package com.rootradar.demo.payment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates schema demo_payments and its table at startup (idempotent). Not part of the measured telemetry. */
@Component
public class SchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS demo_payments");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS demo_payments.payments (
                    id          BIGSERIAL PRIMARY KEY,
                    order_id    TEXT        NOT NULL,
                    amount      NUMERIC(12,2) NOT NULL,
                    status      TEXT        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
    }
}
