package com.example.frauddetection.domain;

import java.time.Duration;

public record AmountDeviationRule(
                boolean enabled,
                int sigmaThreshold,
                Duration historyDuration,
                int minTransactions) {
}
