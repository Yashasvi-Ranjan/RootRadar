package com.rootradar.demo.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.demo.common.ApiException;
import com.rootradar.demo.common.DownstreamClient;
import com.rootradar.demo.common.TelemetryRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {
    public record Item(String sku, int quantity) {}

    public record CheckoutRequest(String customerId, List<Item> items, BigDecimal amount) {}

    private final DownstreamClient orders;

    public CheckoutController(TelemetryRegistry telemetry, ObjectMapper mapper,
                              @Value("${demo.downstream.url:http://127.0.0.1:8082}") String orderUrl,
                              @Value("${demo.downstream.read-timeout-ms:8000}") int readTimeoutMs) {
        this.orders = new DownstreamClient("order-service", orderUrl, 2000, readTimeoutMs, telemetry, mapper);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody CheckoutRequest req) {
        validate(req);
        DownstreamClient.Response r = orders.call(HttpMethod.POST, "/api/orders", req);
        if (r.status() != 201 || r.body() == null) {
            throw new ApiException.DownstreamFailure("order-service responded with status " + r.status(), null);
        }
        JsonNode b = r.body();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkoutId", "chk-" + UUID.randomUUID().toString().substring(0, 8));
        out.put("orderId", b.path("orderId").asText());
        out.put("paymentId", b.path("paymentId"));
        out.put("status", "CONFIRMED");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<JsonNode> get(@PathVariable String orderId) {
        DownstreamClient.Response r = orders.call(HttpMethod.GET, "/api/orders/{id}".replace("{id}", orderId), null);
        if (r.status() == 404) {
            throw new ApiException.NotFound("Order not found: " + orderId);
        }
        if (!r.ok() || r.body() == null) {
            throw new ApiException.DownstreamFailure("order-service responded with status " + r.status(), null);
        }
        return ResponseEntity.ok(r.body());
    }

    private static void validate(CheckoutRequest r) {
        if (r == null || r.customerId() == null || r.customerId().isBlank()) {
            throw new ApiException.Validation("customerId must not be blank");
        }
        if (r.items() == null || r.items().isEmpty()) {
            throw new ApiException.Validation("items must not be empty");
        }
        for (Item i : r.items()) {
            if (i == null || i.sku() == null || i.sku().isBlank() || i.quantity() < 1) {
                throw new ApiException.Validation("each item needs a sku and quantity >= 1");
            }
        }
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new ApiException.Validation("amount must be > 0");
        }
    }
}
