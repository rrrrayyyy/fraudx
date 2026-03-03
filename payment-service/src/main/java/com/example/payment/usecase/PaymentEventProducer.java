package com.example.payment.usecase;

import com.example.proto.Event.*;

public interface PaymentEventProducer {
	void publish(PaymentEventKey key, PaymentEventValue value);
}
