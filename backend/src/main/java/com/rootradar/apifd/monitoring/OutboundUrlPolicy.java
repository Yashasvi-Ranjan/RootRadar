package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSRF policy for every outbound call the monitor makes. The backend is NOT a generic fetcher:
 * <ul>
 *   <li>scheme must be in {@code apifd.monitoring.allowed-schemes} (default http, https);</li>
 *   <li>host must be an exact (case-insensitive) member of {@code apifd.monitoring.allowed-hosts}
 *       (default localhost, 127.0.0.1) - no wildcards, no suffix matching;</li>
 *   <li>no user-info, query or fragment in the base URL, authority must be exactly host[:port];</li>
 *   <li>health/telemetry paths must start with '/' and may not contain '..', backslashes, control characters
 *       or start with '//'; the final URL is re-parsed and its scheme/host/port must equal the validated base.</li>
 * </ul>
 * Redirects are never followed (see {@link SafeHttpClient}). Hosts are allowlisted by name, so an operator who allows
 * a DNS name trusts what it resolves to.
 * Violations raise {@code BadRequest("URL_NOT_ALLOWED")}.
 */
@Component
public class OutboundUrlPolicy {

    private final Set<String> hosts;
    private final Set<String> schemes;

    @Autowired
    public OutboundUrlPolicy(ApifdProperties props) {
        this(props.monitoring().allowedHosts(), props.monitoring().allowedSchemes());
    }

    public OutboundUrlPolicy(Collection<String> allowedHosts, Collection<String> allowedSchemes) {
        this.hosts = allowedHosts.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.schemes = allowedSchemes.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> allowedHosts() {
        return hosts;
    }

    /** Validates a service base URL and returns the parsed URI. */
    public URI validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) throw reject("base URL is required");
        if (baseUrl.length() > 500) throw reject("base URL is too long");
        if (hasIllegalChars(baseUrl)) throw reject("base URL contains illegal characters");
        URI u;
        try {
            u = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw reject("base URL is not a valid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!schemes.contains(scheme)) throw reject("scheme '" + scheme + "' is not allowed (allowed: " + schemes + ")");
        if (u.getRawUserInfo() != null) throw reject("user-info in URL is not allowed");
        if (u.getRawQuery() != null || u.getRawFragment() != null) {
            throw reject("query and fragment are not allowed in a base URL");
        }
        String host = u.getHost();
        if (host == null) throw reject("URL has no valid host");
        int port = u.getPort();
        String expectedAuthority = host + (port == -1 ? "" : ":" + port);
        if (u.getRawAuthority() == null || !u.getRawAuthority().equalsIgnoreCase(expectedAuthority)) {
            throw reject("URL authority is not a plain host[:port]");
        }
        if (port != -1 && (port < 1 || port > 65535)) throw reject("port out of range");
        if (!hosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw reject("host '" + host + "' is not in the allowlist " + hosts);
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        if (path.contains("..")) throw reject("path traversal is not allowed");
        return u;
    }

    /** Validates base URL + path and returns the final request URI (path may carry a query string). */
    public URI resolve(String baseUrl, String path) {
        URI base = validateBaseUrl(baseUrl);
        validatePath(path);
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        while (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        URI result;
        try {
            result = new URI(base.getScheme() + "://" + base.getRawAuthority() + basePath + path);
        } catch (URISyntaxException e) {
            throw reject("resulting URL is not valid");
        }
        if (result.getRawUserInfo() != null || !base.getScheme().equalsIgnoreCase(result.getScheme())
                || result.getHost() == null || !base.getHost().equalsIgnoreCase(result.getHost())
                || base.getPort() != result.getPort() || result.getRawFragment() != null) {
            throw reject("resulting URL differs from the validated base URL");
        }
        return result;
    }

    /** Validates an endpoint path such as /health (optionally with a query string). */
    public void validatePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) throw reject("path must start with a single '/'");
        if (path.length() > 600 || hasIllegalChars(path) || path.contains("..") || path.contains("#")) {
            throw reject("path contains illegal characters");
        }
    }

    private static boolean hasIllegalChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') return true;
        }
        return false;
    }

    private static ApiException.BadRequest reject(String why) {
        return new ApiException.BadRequest("URL_NOT_ALLOWED", "URL not allowed: " + why);
    }
}
