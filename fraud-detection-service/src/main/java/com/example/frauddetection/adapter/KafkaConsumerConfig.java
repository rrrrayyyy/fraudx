package com.example.frauddetection.adapter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import com.example.frauddetection.domain.RetryPolicy;

@Configuration
@EnableConfigurationProperties(KafkaConsumerProperties.class)
public class KafkaConsumerConfig {
    @Bean
    public RetryPolicy retryPolicy(KafkaConsumerProperties properties) {
        return new RetryPolicy(properties.maxRetries(), properties.maxBackoffMs());
    }
}
