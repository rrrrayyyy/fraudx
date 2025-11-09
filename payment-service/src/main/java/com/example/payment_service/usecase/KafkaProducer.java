package com.example.payment_service.usecase;

import com.example.payment.Payment.PaymentEventValue;

public interface KafkaProducer {
	void send(String payload);

	void send(PaymentEventValue value);
}
