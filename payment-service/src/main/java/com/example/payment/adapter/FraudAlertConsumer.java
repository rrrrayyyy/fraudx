package com.example.payment.adapter;

import java.time.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.payment.domain.*;
import com.example.proto.*;

@Service
public class FraudAlertConsumer {
    private static final Logger log = LoggerFactory.getLogger(FraudAlertConsumer.class);
    private final AlertStore alertStore;
    private final BlockedCards blockedCards;

    public FraudAlertConsumer(AlertStore alertStore, BlockedCards blockedCards) {
        this.alertStore = alertStore;
        this.blockedCards = blockedCards;
    }

    @KafkaListener(topics = "${kafka.topics.fraud-alerts.name}", groupId = "payment-alert-handler")
    public void onAlert(ConsumerRecord<FraudAlertKey, FraudAlertValue> record) {
        if (record.key() == null || record.value() == null) {
            return;
        }
        var arrivedAt = Instant.now();
        var alert = record.value();
        var cardId = record.key().getCardId();
        var triggerCreatedAt = Instant.ofEpochSecond(
                alert.getTriggerCreatedAt().getSeconds(), alert.getTriggerCreatedAt().getNanos());
        long latencyMs = Duration.between(triggerCreatedAt, arrivedAt).toMillis();
        alertStore.recordAlert(cardId, latencyMs);
        blockedCards.add(cardId);
        log.info("🚨 Fraud alert received: card={}, trigger_created_at={}, latency={}ms", cardId, triggerCreatedAt,
                latencyMs);
    }
}
