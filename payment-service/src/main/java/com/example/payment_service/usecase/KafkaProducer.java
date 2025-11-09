package com.example.payment_service.usecase;

public interface KafkaProducer {
    void send(String key, String payload);
}
