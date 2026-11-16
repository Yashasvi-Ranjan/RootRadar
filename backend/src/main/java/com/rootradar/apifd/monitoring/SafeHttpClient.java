package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The only outbound HTTP client of the backend. Wraps {@link java.net.http.HttpClient} with:
 * SSRF validation immediately before every call ({@link OutboundUrlPolicy}), redirects never followed, HTTP/1.1,
 * a hard total deadline (connect + headers + body) equal to the caller's timeout, and a response body cap
 * ({@code apifd.monitoring.max-response-bytes}); an oversized body aborts the transfer.
 * Failures are reported as {@link FetchException} carrying the matching {@link HealthOutcome}.
 */
@Component
public class SafeHttpClient {

    /** A completed HTTP exchange (any status code). */
    public record Response(int status, byte[] body, long elapsedMs) {}

    /** Network-level failure; {@code outcome} is never SUCCESS or HTTP_ERROR. */
    public static class FetchException extends Exception {
        private final HealthOutcome outcome;
        private final long elapsedMs;
        private final Integer httpStatus;

        public FetchException(HealthOutcome outcome, String message, long elapsedMs, Integer httpStatus) {
            super(message);
            this.outcome = outcome;
            this.elapsedMs = elapsedMs;
            this.httpStatus = httpStatus;
        }

        public HealthOutcome outcome() { return outcome; }
        public long elapsedMs() { return elapsedMs; }
        public Integer httpStatus() { return httpStatus; }
    }

    private final OutboundUrlPolicy policy;
    private final long maxBytes;
    private final HttpClient client;

    public SafeHttpClient(OutboundUrlPolicy policy, ApifdProperties props) {
        this.policy = policy;
        this.maxBytes = props.monitoring().maxResponseBytes();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PreDestroy
    void shutdown() {
        client.shutdownNow();
    }

    /** GET baseUrl + path (validated). Blocks the calling (pool) thread for at most {@code timeoutMs}. */
    public Response get(String baseUrl, String path, int timeoutMs) throws FetchException {
        URI uri;
        try {
            uri = policy.resolve(baseUrl, path);
        } catch (ApiException.BadRequest e) {
            throw new FetchException(HealthOutcome.BLOCKED, e.getMessage(), 0, null);
        }
        long t0 = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json, */*")
                .header("User-Agent", "apifd-monitor/0.1")
                .GET().build();
        CompletableFuture<HttpResponse<CappedBody>> future =
                client.sendAsync(request, info -> new CappedSubscriber(maxBytes));
        try {
            HttpResponse<CappedBody> resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = elapsedMs(t0);
            CappedBody body = resp.body();
            if (body.overflow()) {
                throw new FetchException(HealthOutcome.INVALID_RESPONSE,
                        "Response body larger than " + maxBytes + " bytes", elapsed, resp.statusCode());
            }
            return new Response(resp.statusCode(), body.bytes(), elapsed);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new FetchException(HealthOutcome.TIMEOUT, "No complete response within " + timeoutMs + " ms",
                    elapsedMs(t0), null);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof HttpTimeoutException) {
                throw new FetchException(HealthOutcome.TIMEOUT, "Request timed out after " + timeoutMs + " ms",
                        elapsedMs(t0), null);
            }
            throw new FetchException(HealthOutcome.CONNECTION_FAILED, describe(c), elapsedMs(t0), null);
        } catch (CancellationException e) {
            throw new FetchException(HealthOutcome.CONNECTION_FAILED, "Request cancelled", elapsedMs(t0), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new FetchException(HealthOutcome.CONNECTION_FAILED, "Interrupted", elapsedMs(t0), null);
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static long elapsedMs(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    record CappedBody(byte[] bytes, boolean overflow) {}

    /** Collects at most {@code cap} bytes; cancels the transfer when exceeded. */
    static final class CappedSubscriber implements HttpResponse.BodySubscriber<CappedBody> {
        private final long cap;
        private final CompletableFuture<CappedBody> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean done;

        CappedSubscriber(long cap) {
            this.cap = cap;
        }

        @Override
        public CompletableFuture<CappedBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) return;
            for (ByteBuffer bb : items) {
                if (buf.size() + (long) bb.remaining() > cap) {
                    done = true;
                    subscription.cancel();
                    result.complete(new CappedBody(new byte[0], true));
                    return;
                }
                byte[] b = new byte[bb.remaining()];
                bb.get(b);
                buf.write(b, 0, b.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!done) {
                done = true;
                result.completeExceptionally(t instanceof IOException ? t : new IOException(t));
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                result.complete(new CappedBody(buf.toByteArray(), false));
            }
        }
    }
}
