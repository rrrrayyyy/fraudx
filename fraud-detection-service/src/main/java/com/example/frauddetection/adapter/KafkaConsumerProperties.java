package com.example.frauddetection.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka.consumer")
public record KafkaConsumerProperties(int maxRetries, long maxBackoffMs) {
}
