package com.example.frauddetection.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.frauddetection.domain.*;

@Component
@ConfigurationProperties(prefix = "fraud-detection.rules.transaction-frequency")
public class TransactionFrequencyProperties {
    private Map<AttributeKey, TransactionFrequencyRule> targetAttributes;

    public Map<AttributeKey, TransactionFrequencyRule> getTargetAttributes() {
        return targetAttributes;
    }

    public void setTargetAttributes(Map<AttributeKey, TransactionFrequencyRule> attributes) {
        this.targetAttributes = attributes;
    }
}
