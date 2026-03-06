package com.example.frauddetection.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.frauddetection.domain.*;

@ConfigurationProperties(prefix = "fraud-detection.rules")
public record FraudRulesProperties(
                TargetAttributesWrapper<TransactionFrequencyRule> transactonFrequency,
                AmountDeviationRule amountDeviation,
                TargetAttributesWrapper<UserLinkageRule> userLinkage) {
        public record TargetAttributesWrapper<T>(
                        Map<String, T> targetAttributes) {
        }
}
