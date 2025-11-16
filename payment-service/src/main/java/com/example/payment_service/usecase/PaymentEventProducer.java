package com.example.payment_service.usecase;

import com.example.payment.Payment.PaymentEventValue;

public interface PaymentEventProducer {
	void publish(PaymentEventValue value);
}
