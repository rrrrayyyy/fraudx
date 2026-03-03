package com.example.frauddetection.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.frauddetection.domain.*;

@ConfigurationProperties(prefix = "fraud-detection.rules.transaction-frequency")
public record TransactionFrequencyProperties(Map<AttributeKey, TransactionFrequencyRule> attributes) {
}
