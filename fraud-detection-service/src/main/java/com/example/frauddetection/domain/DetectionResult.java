package com.example.frauddetection.domain;

import java.time.Instant;

public record DetectionResult(String cardId, Instant clusterTimestamp, Instant triggerCreatedAt) {
}
