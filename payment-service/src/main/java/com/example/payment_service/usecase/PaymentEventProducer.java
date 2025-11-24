package com.example.payment_service.usecase;

import com.example.proto.Event.*;

public interface PaymentEventProducer {
	void publish(PaymentEventKey key, PaymentEventValue value);
}
