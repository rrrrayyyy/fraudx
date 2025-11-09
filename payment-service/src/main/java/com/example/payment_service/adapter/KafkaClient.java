package com.example.payment_service.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment_service.usecase.KafkaProducer;

@Service
public class KafkaClient implements KafkaProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopics kafkaTopics;

    public KafkaClient(KafkaTemplate<String, String> kafkaTemplate, KafkaTopics kafkaTopics) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopics = kafkaTopics;
    }

    @Override
    public void send(String key, String payload) {
        var t = kafkaTopics.get(key);
        kafkaTemplate.send(t, payload);
        System.out.println("✅ Published payment event: " + payload);
    }
}
