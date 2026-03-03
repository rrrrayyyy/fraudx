package com.example.frauddetection.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.frauddetection.domain.AmountDeviationRule;

@Component
@ConfigurationProperties(prefix = "fraud-detection.rules.amount-deviation")
public class AmountDeviationProperties {
    private AmountDeviationRule rule;

    public AmountDeviationRule getRule() {
        return rule;
    }

    public void setRule(AmountDeviationRule rule) {
        this.rule = rule;
    }
}
