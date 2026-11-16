package com.rootradar.demo.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest r) {
        return body(e.status(), e.code(), e.getMessage(), r.getRequestURI());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest r) {
        return body(400, "BAD_REQUEST", "Malformed request", r.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest r) {
        if (e instanceof ErrorResponse er) {
            HttpStatusCode sc = er.getStatusCode();
            String code = sc.value() == 404 ? "NOT_FOUND" : sc.value() == 405 ? "METHOD_NOT_ALLOWED" : "BAD_REQUEST";
            return body(sc.value(), code, code.toLowerCase().replace('_', ' '), r.getRequestURI());
        }
        log.error("Unhandled exception on {}", r.getRequestURI(), e);
        return body(500, "INTERNAL_ERROR", "Unexpected error", r.getRequestURI());
    }

    public static Map<String, Object> errorMap(int status, String code, String message, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status);
        String reason;
        try {
            reason = HttpStatus.valueOf(status).getReasonPhrase();
        } catch (IllegalArgumentException ex) {
            reason = "Error";
        }
        m.put("error", reason);
        m.put("code", code);
        m.put("message", message);
        m.put("path", path);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message, String path) {
        return ResponseEntity.status(status).body(errorMap(status, code, message, path));
    }
}
