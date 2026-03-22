package com.example.frauddetection.usecase;

import com.example.frauddetection.domain.DetectionResult;

public interface FraudAlertPublisher {
    void publish(DetectionResult result);
}
