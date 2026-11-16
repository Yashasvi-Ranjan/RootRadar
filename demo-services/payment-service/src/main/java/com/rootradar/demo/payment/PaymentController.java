package com.rootradar.demo.payment;

import com.rootradar.demo.common.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    public record PaymentRequest(String orderId, BigDecimal amount) {}

    private final PaymentRepository repo;

    public PaymentController(PaymentRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody PaymentRequest req) {
        if (req == null || req.orderId() == null || req.orderId().isBlank()) {
            throw new ApiException.Validation("orderId must not be blank");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new ApiException.Validation("amount must be > 0");
        }
        long id = repo.insert(req.orderId(), req.amount());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", id);
        out.put("status", "APPROVED");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> p = repo.find(id);
        if (p == null) {
            throw new ApiException.NotFound("Payment not found: " + id);
        }
        return p;
    }
}
