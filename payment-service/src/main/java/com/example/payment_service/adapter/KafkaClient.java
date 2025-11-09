package com.example.payment_service.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.example.payment_service.usecase.KafkaProducer;

@Service
public class KafkaClient implements KafkaProducer {
    // private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTemplate<String, PaymentEventValue> paymentEventTemplate;
    private final KafkaTopics kafkaTopics;

    public KafkaClient(
            // KafkaTemplate<String, String> kafkaTemplate,
            KafkaTemplate<String, PaymentEventValue> paymentEventTemplate,
            KafkaTopics kafkaTopics) {
        // this.kafkaTemplate = kafkaTemplate;
        this.paymentEventTemplate = paymentEventTemplate;
        this.kafkaTopics = kafkaTopics;
    }

    // @Override
    // public void send(String topicKey, String payload) {
    // var t = kafkaTopics.get(topicKey);
    // kafkaTemplate.send(t, payload);
    // System.out.println("✅ Published payment event: " + payload);
    // }

    @Override
    public void sendPaymentEvent(PaymentEventValue value) {
        var t = kafkaTopics.get(KafkaTopic.PAYMENT.key);
        paymentEventTemplate.send(t, value);
        System.out.println("✅ Published payment event: " + value);
    }
}
