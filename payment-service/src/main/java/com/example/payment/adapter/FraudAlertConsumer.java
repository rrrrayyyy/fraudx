package com.example.payment.adapter;

import java.time.Instant;

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
        var alert = record.value();
        alertStore.put(alert.getBatchId(), Instant.now());
        blockedCards.add(record.key().getCardId());
        log.info("🚨 Fraud alert received: card={}, batch={}", record.key().getCardId(), alert.getBatchId());
    }
}
