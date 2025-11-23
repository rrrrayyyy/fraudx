package com.example.payment_service.usecase;

import com.example.payment.Payment.*;

public interface PaymentEventProducer {
	void publish(PaymentEventKey key, PaymentEventValue value);
}
