package com.example.frauddetection.usecase;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.common.adapter.FraudRulesProperties;
import com.example.frauddetection.domain.*;

@Service
public class PaymentEventsConsumeUseCase {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumeUseCase.class);
    private final PaymentEventRepository repository;
    private final FraudAlertPublisher alertPublisher;
    private final FraudRulesProperties rulesProperties;
    private final RetryPolicy retryPolicy;
    private final ConcurrentHashMap<String, Set<Instant>> publishedAlerts = new ConcurrentHashMap<>();

    public PaymentEventsConsumeUseCase(PaymentEventRepository repository, FraudAlertPublisher alertPublisher,
            FraudRulesProperties rulesProperties, RetryPolicy retryPolicy) {
        this.repository = repository;
        this.alertPublisher = alertPublisher;
        this.rulesProperties = rulesProperties;
        this.retryPolicy = retryPolicy;
    }

    public void execute(List<PaymentEvent> events) {
        var failed = repository.insertAll(events);

        for (int retry = 0; 0 < failed.size() && retry < retryPolicy.maxRetries(); retry++) {
            retryPolicy.backoff(retry);
            log.warn("♻️ Retrying {} failed records (retry {}/{})",
                    failed.size(), retry + 1, retryPolicy.maxRetries());
            failed = repository.insertAll(failed);
        }
        if (0 < failed.size()) {
            log.error("❌ {} records failed after {} retries, skipping", failed.size(), retryPolicy.maxRetries());
        }

        var rule = rulesProperties.transactionFrequency().targetAttributes().get("card-id");
        if (rule != null && rule.enabled()) {
            var detections = repository.detectFraud(events, rule.threshold(), rule.duration());
            for (var detection : detections) {
                var timestamps = publishedAlerts.computeIfAbsent(detection.cardId(),
                        k -> ConcurrentHashMap.newKeySet());
                if (timestamps.add(detection.clusterTimestamp())) {
                    log.info("🚨 Fraud detected: card={}, cluster={}", detection.cardId(),
                            detection.clusterTimestamp());
                    alertPublisher.publish(detection);
                }
            }
        }
    }
}
