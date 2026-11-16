package com.rootradar.apifd.graph;

/** Raised by {@link DependencyGraph} validation. Pure (no Spring). The service layer maps reasons to HTTP codes. */
public class InvalidGraphException extends RuntimeException {

    public enum Reason { SELF_LOOP, UNKNOWN_NODE, DUPLICATE_EDGE, CYCLE }

    private final Reason reason;

    public InvalidGraphException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
