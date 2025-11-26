package com.example.fraud_detection_service.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.fraud_detection_service.domain.*;

@Component
@ConfigurationProperties(prefix = "fraud-detection.rules.user-linkage")
public class UserLinkageProperties {
    private Map<AttributeKey, UserLinkageRule> targetAttributes;

    public Map<AttributeKey, UserLinkageRule> getTargetAttributes() {
        return targetAttributes;
    }

    public void setTargetAttributes(Map<AttributeKey, UserLinkageRule> attributes) {
        this.targetAttributes = attributes;
    }
}
