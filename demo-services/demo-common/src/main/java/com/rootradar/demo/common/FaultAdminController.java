package com.rootradar.demo.common;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault-injection admin API. Registered ONLY under profile {@code demo} and only when DEMO_ADMIN_TOKEN is not blank
 * (otherwise the paths do not exist: 404). Refuses to start when the server is not bound to a loopback address.
 */
@RestController
@Profile("demo")
@ConditionalOnExpression("!'${DEMO_ADMIN_TOKEN:demo-admin-token}'.isBlank()")
@RequestMapping("/admin/faults")
public class FaultAdminController {
    private final FaultService faults;
    private final String token;
    private final String bindAddress;

    public FaultAdminController(FaultService faults,
                                @Value("${DEMO_ADMIN_TOKEN:demo-admin-token}") String token,
                                @Value("${server.address:}") String bindAddress) {
        this.faults = faults;
        this.token = token;
        this.bindAddress = bindAddress;
    }

    @PostConstruct
    void requireLoopback() {
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(bindAddress)) {
            throw new IllegalStateException(
                    "Refusing to enable the demo admin API: server.address must be loopback but is '" + bindAddress + "'");
        }
    }

    @GetMapping
    public FaultState get(@RequestHeader(value = "X-Admin-Token", required = false) String t) {
        auth(t);
        return faults.state();
    }

    @PutMapping
    public FaultState put(@RequestHeader(value = "X-Admin-Token", required = false) String t,
                          @RequestBody(required = false) FaultConfig body) {
        auth(t);
        return faults.replace(body == null ? FaultConfig.none() : body);
    }

    @DeleteMapping
    public FaultState delete(@RequestHeader(value = "X-Admin-Token", required = false) String t) {
        auth(t);
        return faults.clear();
    }

    private void auth(String supplied) {
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
        if (supplied == null || !MessageDigest.isEqual(a, b)) {
            throw new ApiException.Unauthorized();
        }
    }
}
