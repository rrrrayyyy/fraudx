package com.example.payment_service.usecase;

import com.example.payment.Payment.PaymentEventValue;

public interface KafkaProducer {
	void sendStringPaymentEvent(String payload);

	void sendPaymentEvent(PaymentEventValue value);
}
