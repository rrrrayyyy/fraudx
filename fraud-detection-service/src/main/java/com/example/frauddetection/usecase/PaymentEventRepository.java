package com.example.frauddetection.usecase;

import java.time.Duration;
import java.util.*;

import com.example.frauddetection.domain.*;

public interface PaymentEventRepository {
    List<PaymentEvent> insertAll(List<PaymentEvent> events);

    List<DetectionResult> detectFraud(Set<String> cardIds, int threshold, Duration duration, int lookback);
}
