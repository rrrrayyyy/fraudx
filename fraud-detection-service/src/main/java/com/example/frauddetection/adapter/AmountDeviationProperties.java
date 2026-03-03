package com.example.frauddetection.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.frauddetection.domain.AmountDeviationRule;

@ConfigurationProperties(prefix = "fraud-detection.rules.amount-deviation")
public record AmountDeviationProperties(AmountDeviationRule rule) {
}
