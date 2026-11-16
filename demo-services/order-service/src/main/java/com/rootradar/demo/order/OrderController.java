package com.rootradar.demo.order;

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
@RequestMapping("/api/orders")
public class OrderController {
    public record Item(String sku, int quantity) {}

    public record OrderRequest(String customerId, List<Item> items, BigDecimal amount) {}

    private static final int MAX_ORDERS = 1000;

    private final DownstreamClient payments;
    /** Bounded in-memory store (access-ordered LRU). */
    private final Map<String, Map<String, Object>> store = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
            return size() > MAX_ORDERS;
        }
    };

    public OrderController(TelemetryRegistry telemetry, ObjectMapper mapper,
                           @Value("${demo.downstream.url:http://127.0.0.1:8083}") String paymentUrl,
                           @Value("${demo.downstream.read-timeout-ms:5000}") int readTimeoutMs) {
        this.payments = new DownstreamClient("payment-service", paymentUrl, 2000, readTimeoutMs, telemetry, mapper);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody OrderRequest req) {
        validate(req);
        String orderId = "o-" + UUID.randomUUID().toString().substring(0, 8);
        DownstreamClient.Response r = payments.call(HttpMethod.POST, "/api/payments",
                Map.of("orderId", orderId, "amount", req.amount()));
        if (r.status() != 201 || r.body() == null) {
            throw new ApiException.DownstreamFailure("payment-service responded with status " + r.status(), null);
        }
        JsonNode b = r.body();
        JsonNode paymentId = b.path("paymentId");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("customerId", req.customerId());
        order.put("amount", req.amount());
        order.put("status", "PAID");
        order.put("paymentId", paymentId);
        synchronized (store) {
            store.put(orderId, order);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("paymentId", paymentId);
        out.put("status", "PAID");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Map<String, Object> o;
        synchronized (store) {
            o = store.get(id);
        }
        if (o == null) {
            throw new ApiException.NotFound("Order not found: " + id);
        }
        return o;
    }

    private static void validate(OrderRequest r) {
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
