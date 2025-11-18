package com.example.payment_service.adapter;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.example.payment_service.usecase.PaymentEventProducer;

@Service
public class KafkaClient implements PaymentEventProducer {
	private final KafkaTemplate<String, PaymentEventValue> protoTemplate;

	public KafkaClient(KafkaTemplate<String, PaymentEventValue> protoTemplate) {
		this.protoTemplate = protoTemplate;
	}

	@Override
	public void publish(PaymentEventValue value) {
		protoTemplate.send("payment-events", value);
	}
}
