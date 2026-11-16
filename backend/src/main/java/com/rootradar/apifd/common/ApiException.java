package com.rootradar.apifd.common;

import org.springframework.http.HttpStatus;

/**
 * Base for domain exceptions translated by {@link GlobalExceptionHandler}. Use the subclasses; services throw,
 * controllers never catch.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    /** 404 NOT_FOUND. */
    public static class NotFound extends ApiException {
        public NotFound(String message) { super(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    }

    /** 400 with an explicit code (e.g. URL_NOT_ALLOWED, INVALID_GRAPH, INVALID_RANGE). */
    public static class BadRequest extends ApiException {
        public BadRequest(String code, String message) { super(HttpStatus.BAD_REQUEST, code, message); }
    }

    /** 409 CONFLICT (duplicate name, duplicate edge, cycle). */
    public static class Conflict extends ApiException {
        public Conflict(String code, String message) { super(HttpStatus.CONFLICT, code, message); }
    }
}
