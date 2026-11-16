package com.rootradar.apifd.common;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error body for every non-2xx REST response (see docs/API_CONTRACTS.md section 1.3).
 *
 * @param timestamp server time
 * @param status    HTTP status code
 * @param error     HTTP reason phrase, e.g. "Bad Request"
 * @param code      stable machine-readable code, e.g. VALIDATION_FAILED, NOT_FOUND, CONFLICT, URL_NOT_ALLOWED,
 *                  INVALID_GRAPH, INTERNAL_ERROR
 * @param message   human-readable message (never contains stack traces / SQL)
 * @param path      request path
 * @param details   per-field problems for validation errors, otherwise empty
 */
public record ApiError(Instant timestamp, int status, String error, String code, String message, String path,
                       List<FieldProblem> details) {

    public record FieldProblem(String field, String message) {}
}
