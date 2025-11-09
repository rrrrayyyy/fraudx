package com.example.payment_service.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.example.payment_service.usecase.KafkaProducer;

@Service
public class KafkaClient implements KafkaProducer {
	private final KafkaTopicConfig topicConfig;
	private final KafkaTemplate<String, String> stringTemplate;
	private final KafkaTemplate<String, PaymentEventValue> protoTemplate;

	public KafkaClient(
			KafkaTopicConfig topicConfig,
			KafkaTemplate<String, String> stringTemplate,
			KafkaTemplate<String, PaymentEventValue> protoTemplate) {
		this.topicConfig = topicConfig;
		this.stringTemplate = stringTemplate;
		this.protoTemplate = protoTemplate;
	}

	@Override
	public void send(String payload) {
		var t = topicConfig.getTopicName(KafkaTopic.PAYMENT.key);
		stringTemplate.send(t, payload);
		System.out.println("✅ Published payment event as String: " + payload);
	}

	@Override
	public void send(PaymentEventValue value) {
		var t = topicConfig.getTopicName(KafkaTopic.PAYMENT.key);
		protoTemplate.send(t, value);
		System.out.println("✅ Published payment event: " + value);
	}
}
