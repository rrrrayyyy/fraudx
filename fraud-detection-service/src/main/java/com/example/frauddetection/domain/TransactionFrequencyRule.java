package com.example.frauddetection.domain;

import java.time.Duration;

public record TransactionFrequencyRule(
                boolean enabled,
                int threshold,
                Duration duration) {
}
