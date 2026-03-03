package com.example.frauddetection.adapter;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.frauddetection.domain.*;

@ConfigurationProperties(prefix = "fraud-detection.rules.user-linkage")
public record UserLinkageProperties(Map<AttributeKey, UserLinkageRule> attributes) {
}
