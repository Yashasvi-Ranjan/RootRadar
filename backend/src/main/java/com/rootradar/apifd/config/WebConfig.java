package com.rootradar.apifd.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS restricted to the configured frontend origin (apifd.frontend-origin). Applies to /api/** incl. SSE. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApifdProperties props;

    public WebConfig(ApifdProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.frontendOrigin())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
