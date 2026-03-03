package com.example.frauddetection.domain;

public record UserLinkageRule(
        boolean enabled,
        int linkageThreshold) {
}
