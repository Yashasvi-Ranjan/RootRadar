package com.rootradar.demo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies the active fault to business (/api/**) requests, after the route is known and inside the timed filter. */
public class FaultInterceptor implements HandlerInterceptor {
    private final FaultService faults;
    private final ObjectMapper mapper;

    public FaultInterceptor(FaultService faults, ObjectMapper mapper) {
        this.faults = faults;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        FaultConfig c = faults.current();
        if (!c.active()) {
            return true;
        }
        if (c.unavailable()) {
            return fail(req, res, 503, "UNAVAILABLE", "Injected fault: service unavailable");
        }
        if (faults.shouldFail(c)) {
            return fail(req, res, c.errorStatus(), "INJECTED_ERROR", "Injected fault: error");
        }
        long sleep = faults.latencyToInjectMs(c);
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    private boolean fail(HttpServletRequest req, HttpServletResponse res, int status, String code, String msg)
            throws Exception {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                GlobalExceptionHandler.errorMap(status, code, msg, req.getRequestURI()));
        return false;
    }
}
