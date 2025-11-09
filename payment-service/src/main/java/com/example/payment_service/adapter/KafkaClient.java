package com.example.payment_service.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.example.payment_service.usecase.KafkaProducer;

@Service
public class KafkaClient implements KafkaProducer {
	private final KafkaTopics kafkaTopics;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final KafkaTemplate<String, PaymentEventValue> paymentEventTemplate;

	public KafkaClient(
			KafkaTopics kafkaTopics,
			KafkaTemplate<String, String> kafkaTemplate,
			KafkaTemplate<String, PaymentEventValue> paymentEventTemplate) {
		this.kafkaTopics = kafkaTopics;
		this.kafkaTemplate = kafkaTemplate;
		this.paymentEventTemplate = paymentEventTemplate;
	}

	@Override
	public void send(String payload) {
		var t = kafkaTopics.get(KafkaTopic.PAYMENT.key);
		kafkaTemplate.send(t, payload);
		System.out.println("✅ Published payment event as String: " + payload);
	}

	@Override
	public void send(PaymentEventValue value) {
		var t = kafkaTopics.get(KafkaTopic.PAYMENT.key);
		paymentEventTemplate.send(t, value);
		System.out.println("✅ Published payment event: " + value);
	}
}
