package com.example.frauddetection.domain;

public record TransactionFrequencyRule(
        boolean enabled,
        int threshold,
        int duration,
        TimeUnit timeUnit) {
}
