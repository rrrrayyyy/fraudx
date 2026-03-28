package com.example.frauddetection.adapter;

import java.time.Instant;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.frauddetection.domain.DetectionResult;
import com.example.frauddetection.usecase.FraudAlertPublisher;
import com.example.proto.*;
import com.google.protobuf.Timestamp;

@Service
public class KafkaFraudAlertPublisher implements FraudAlertPublisher {
    private final KafkaTemplate<FraudAlertKey, FraudAlertValue> kafkaTemplate;
    private final String topic;

    public KafkaFraudAlertPublisher(KafkaTemplate<FraudAlertKey, FraudAlertValue> kafkaTemplate,
            KafkaTopicConfig topicConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topicConfig.getTopic(TopicKey.FRAUD_ALERTS).name();
    }

    @Override
    public void publish(DetectionResult result) {
        var now = Instant.now();
        var key = FraudAlertKey.newBuilder()
                .setCardId(result.cardId())
                .build();
        var value = FraudAlertValue.newBuilder()
                .setDetectedAt(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .setTriggerCreatedAt(Timestamp.newBuilder()
                        .setSeconds(result.triggerCreatedAt().getEpochSecond())
                        .setNanos(result.triggerCreatedAt().getNano())
                        .build())
                .build();
        kafkaTemplate.send(topic, key, value);
    }
}
