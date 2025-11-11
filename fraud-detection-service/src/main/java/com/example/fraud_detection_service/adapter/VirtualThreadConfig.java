package com.example.fraud_detection_service.adapter;

import java.util.concurrent.*;

import org.springframework.context.annotation.*;

@Configuration
public class VirtualThreadConfig {
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
