package com.rootradar.demo.gateway;

import com.rootradar.demo.common.DemoCommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Demo gateway-service (checkout API). See docs/API_CONTRACTS.md section 7. */
@SpringBootApplication
@Import(DemoCommonConfig.class)
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
