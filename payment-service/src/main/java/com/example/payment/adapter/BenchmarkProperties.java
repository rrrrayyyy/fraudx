package com.example.payment.adapter;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark")
public record BenchmarkProperties(int cards, double fraudProbability, int burstMultiplier, Duration jitterMin,
		Duration jitterMax) {
}
