package com.rootradar.demo.payment;

import com.rootradar.demo.common.DemoCommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Demo payment-service (payments). See docs/API_CONTRACTS.md section 7. */
@SpringBootApplication
@Import(DemoCommonConfig.class)
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
