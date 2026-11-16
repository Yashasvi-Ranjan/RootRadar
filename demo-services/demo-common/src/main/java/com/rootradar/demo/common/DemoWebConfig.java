package com.rootradar.demo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class DemoWebConfig implements WebMvcConfigurer {
    private final FaultService faults;
    private final ObjectMapper mapper;

    DemoWebConfig(FaultService faults, ObjectMapper mapper) {
        this.faults = faults;
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FaultInterceptor(faults, mapper)).addPathPatterns("/api/**");
    }
}
