package com.rootradar.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Measures wall-clock time around the whole request (including injected fault latency) and records it per route
 * template. /health, /admin/**, /actuator/**, /internal/** and unmatched routes are not recorded.
 */
public class TelemetryFilter extends OncePerRequestFilter {
    private final TelemetryRegistry telemetry;

    public TelemetryFilter(TelemetryRegistry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        boolean exception = false;
        try {
            chain.doFilter(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            exception = true;
            throw e;
        } finally {
            Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null) {
                boolean error = exception || res.getStatus() >= 500;
                telemetry.recordEndpoint(req.getMethod(), pattern.toString(), (System.nanoTime() - t0) / 1e6, error);
            }
        }
    }
}
