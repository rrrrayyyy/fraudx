package com.example.frauddetection.usecase;

import java.time.Duration;
import java.util.List;

import com.example.frauddetection.domain.*;

public interface PaymentEventRepository {
    List<PaymentEvent> insertAll(List<PaymentEvent> events);

    List<DetectionResult> detectFraud(List<PaymentEvent> events, int threshold, Duration duration);
}
