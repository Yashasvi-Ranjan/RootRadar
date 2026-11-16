package com.rootradar.demo.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real HTTP client towards one downstream service, measured as a telemetry dependency (HTTP). Any non-2xx status,
 * IO error or timeout counts as a dependency error (timeouts additionally as timeoutCount). IO errors/timeouts are
 * raised as {@link ApiException.DownstreamFailure}; non-2xx statuses are returned so the caller can decide.
 */
public class DownstreamClient {
    public record Response(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final String name;
    private final RestClient client;
    private final TelemetryRegistry telemetry;
    private final ObjectMapper mapper;

    public DownstreamClient(String name, String baseUrl, int connectTimeoutMs, int readTimeoutMs,
                            TelemetryRegistry telemetry, ObjectMapper mapper) {
        this.name = name;
        this.telemetry = telemetry;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    public Response call(HttpMethod method, String path, Object body) {
        long t0 = System.nanoTime();
        boolean error = true;
        boolean timeout = false;
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path).accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            Response r = spec.exchange((req, res) -> {
                byte[] bytes = res.getBody().readAllBytes();
                JsonNode node = null;
                if (bytes.length > 0) {
                    try {
                        node = mapper.readTree(bytes);
                    } catch (IOException ignored) {
                        // non-JSON error body: leave null
                    }
                }
                return new Response(res.getStatusCode().value(), node);
            });
            error = !r.ok();
            return r;
        } catch (RestClientException e) {
            timeout = isTimeout(e);
            throw new ApiException.DownstreamFailure(
                    name + (timeout ? " timed out" : " unreachable: " + e.getClass().getSimpleName()), e);
        } finally {
            telemetry.recordDependency(name, "HTTP", (System.nanoTime() - t0) / 1e6, error, timeout);
        }
    }

    static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
