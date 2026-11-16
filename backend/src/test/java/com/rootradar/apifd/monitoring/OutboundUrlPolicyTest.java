package com.rootradar.apifd.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rootradar.apifd.common.ApiException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF policy: explicit allowlist, http/https only, no userinfo/redirect tricks. Pure unit test. */
class OutboundUrlPolicyTest {

    private final OutboundUrlPolicy policy =
            new OutboundUrlPolicy(List.of("localhost", "127.0.0.1"), List.of("http", "https"));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8083", "http://127.0.0.1:8081", "HTTP://LOCALHOST:9", "https://localhost",
            "http://localhost:8083/api"})
    void allowsAllowlistedHosts(String url) {
        policy.validateBaseUrl(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example.com", "http://169.254.169.254/latest/meta-data", "http://10.0.0.5:8080",
            "http://[::1]:8080", "http://0.0.0.0", "http://localhost.evil.com", "http://evil.com#@localhost",
            "http://localhost@evil.com", "http://user:pw@localhost:8080", "http://127.0.0.1%2f@evil.com",
            "file:///etc/passwd", "ftp://localhost/x", "gopher://localhost", "jar:http://localhost!/", "localhost:8080",
            "//localhost", "http://", "", "   ", "http://localhost:8080?x=1", "http://local host", "http://localhost\\@evil.com",
            "http://localhost:99999", "javascript:alert(1)"})
    void rejectsEverythingElse(String url) {
        ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class, () -> policy.validateBaseUrl(url));
        assertEquals("URL_NOT_ALLOWED", e.getCode());
    }

    @Test
    void rejectsNull() {
        assertThrows(ApiException.BadRequest.class, () -> policy.validateBaseUrl(null));
    }

    @Test
    void resolvesPathAgainstValidatedBase() {
        URI u = policy.resolve("http://localhost:8083/", "/health");
        assertEquals("http://localhost:8083/health", u.toString());
        assertEquals("http://localhost:8083/internal/telemetry?since=2026-11-16T04%3A30%3A00Z",
                policy.resolve("http://localhost:8083", "/internal/telemetry?since=2026-11-16T04%3A30%3A00Z").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"health", "//evil.com/x", "/../etc/passwd", "/a b", "/a\\b", "/x#frag", ""})
    void rejectsBadPaths(String path) {
        ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class,
                () -> policy.resolve("http://localhost:8083", path));
        assertEquals("URL_NOT_ALLOWED", e.getCode());
    }

    @Test
    void customAllowlistIsExactMatchOnly() {
        OutboundUrlPolicy p = new OutboundUrlPolicy(List.of("svc.internal"), List.of("https"));
        p.validateBaseUrl("https://svc.internal:8443");
        assertThrows(ApiException.BadRequest.class, () -> p.validateBaseUrl("https://a.svc.internal"));
        assertThrows(ApiException.BadRequest.class, () -> p.validateBaseUrl("http://svc.internal"));
        assertThrows(ApiException.BadRequest.class, () -> p.validateBaseUrl("https://localhost"));
    }
}
