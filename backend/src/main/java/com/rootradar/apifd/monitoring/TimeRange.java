package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.Constants;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Resolved [from, to) query range. Defaults: to = now, from = to - 1h. Rejects from &gt;= to and spans over 31 days. */
public record TimeRange(Instant from, Instant to) {

    public static TimeRange resolve(Instant from, Instant to, Clock clock) {
        Instant t = to != null ? to : clock.instant();
        Instant f = from != null ? from : t.minus(Duration.ofHours(1));
        validate(f, t);
        return new TimeRange(f, t);
    }

    public static void validate(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new ApiException.BadRequest("INVALID_RANGE", "from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new ApiException.BadRequest("INVALID_RANGE", "'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(Constants.MAX_QUERY_RANGE) > 0) {
            throw new ApiException.BadRequest("INVALID_RANGE",
                    "Range exceeds the maximum of " + Constants.MAX_QUERY_RANGE.toDays() + " days");
        }
    }
}
