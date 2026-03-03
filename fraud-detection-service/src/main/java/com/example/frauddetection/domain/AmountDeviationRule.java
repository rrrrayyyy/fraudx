package com.example.frauddetection.domain;

public record AmountDeviationRule(
        boolean enabled,
        int sigmaThreshold,
        int historyDuration,
        TimeUnit timeUnit,
        int minTransactions) {
}
