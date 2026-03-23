package com.example.frauddetection.usecase;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.common.adapter.FraudRulesProperties;
import com.example.frauddetection.adapter.ScyllaProperties;
import com.example.frauddetection.domain.*;

@Service
public class PaymentEventsConsumeUseCase {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumeUseCase.class);
    private final PaymentEventRepository repository;
    private final FraudAlertPublisher alertPublisher;
    private final FraudRulesProperties rulesProperties;
    private final ScyllaProperties scyllaProperties;
    private final RetryPolicy retryPolicy;
    private final Set<String> detectedBatchIds = ConcurrentHashMap.newKeySet();

    public PaymentEventsConsumeUseCase(PaymentEventRepository repository, FraudAlertPublisher alertPublisher,
            FraudRulesProperties rulesProperties, ScyllaProperties scyllaProperties, RetryPolicy retryPolicy) {
        this.repository = repository;
        this.alertPublisher = alertPublisher;
        this.rulesProperties = rulesProperties;
        this.scyllaProperties = scyllaProperties;
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
            var cardIds = events.stream().map(PaymentEvent::cardId).collect(Collectors.toSet());
            var detections = repository.detectFraud(cardIds, rule.threshold(), rule.duration(), scyllaProperties.lookback());
            for (var detection : detections) {
                if (detectedBatchIds.add(detection.batchId())) {
                    log.info("🚨 Fraud detected: card={}, batch={}", detection.cardId(), detection.batchId());
                    alertPublisher.publish(detection);
                }
            }
        }
    }
}
