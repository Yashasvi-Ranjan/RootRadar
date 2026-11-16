package com.rootradar.demo.common;

/** Errors mapped to the contract's ApiError JSON by {@link GlobalExceptionHandler}. */
public abstract class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    protected ApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static class Validation extends ApiException {
        public Validation(String message) {
            super(400, "VALIDATION_FAILED", message, null);
        }
    }

    public static class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(400, "BAD_REQUEST", message, null);
        }
    }

    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super(404, "NOT_FOUND", message, null);
        }
    }

    public static class Unauthorized extends ApiException {
        public Unauthorized() {
            super(401, "UNAUTHORIZED", "Missing or invalid X-Admin-Token", null);
        }
    }

    /** A downstream service failed, timed out or answered non-2xx: surfaces as 502 DOWNSTREAM_FAILURE. */
    public static class DownstreamFailure extends ApiException {
        public DownstreamFailure(String message, Throwable cause) {
            super(502, "DOWNSTREAM_FAILURE", message, cause);
        }
    }
}
