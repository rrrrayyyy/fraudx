package com.example.common.domain;

import java.time.Duration;

public record TransactionFrequencyRule(
		boolean enabled,
		int threshold,
		Duration duration) {
}
