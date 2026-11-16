package com.rootradar.demo.order;

import com.rootradar.demo.common.DemoCommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Demo order-service (orders). See docs/API_CONTRACTS.md section 7. */
@SpringBootApplication
@Import(DemoCommonConfig.class)
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
