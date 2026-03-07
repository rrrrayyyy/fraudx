package com.example.payment.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.usecase.PaymentEventProducer;
import com.example.proto.*;

@Service
public class KafkaClient implements PaymentEventProducer {
	private final KafkaTemplate<PaymentEventKey, PaymentEventValue> protoTemplate;
	private final Topic paymentTopic;

	public KafkaClient(KafkaTemplate<PaymentEventKey, PaymentEventValue> protoTemplate, KafkaTopicConfig topicConfig) {
		this.protoTemplate = protoTemplate;
		paymentTopic = topicConfig.getTopic(TopicKey.PAYMENT);
	}

	@Override
	public void publish(PaymentEventKey key, PaymentEventValue value) {
		protoTemplate.send(paymentTopic.name(), key, value);
	}
}
