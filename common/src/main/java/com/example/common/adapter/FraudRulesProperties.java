package com.example.common.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.common.domain.TransactionFrequencyRule;

@ConfigurationProperties(prefix = "fraud-detection.rules")
public record FraudRulesProperties(
		TargetAttributesWrapper<TransactionFrequencyRule> transactionFrequency) {
	public record TargetAttributesWrapper<T>(
			Map<String, T> targetAttributes) {
	}
}
