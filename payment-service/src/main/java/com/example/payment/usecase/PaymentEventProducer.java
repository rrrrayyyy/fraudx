package com.example.payment.usecase;

import com.example.proto.*;

public interface PaymentEventProducer {
	void publish(PaymentEventKey key, PaymentEventValue value);
}
