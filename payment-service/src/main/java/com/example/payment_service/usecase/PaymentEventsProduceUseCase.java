package com.example.payment_service.usecase;

import java.util.concurrent.*;

import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.github.f4b6a3.uuid.UuidCreator;

@Service
public class PaymentEventsProduceUseCase {
	private final PaymentEventProducer paymentEventProducer;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer) {
		this.paymentEventProducer = paymentEventProducer;
	}

	public void run(int n) {
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				executor.submit(() -> {
					var id = UuidCreator.getTimeOrderedEpoch().toString();
					var event = PaymentEventValue.newBuilder()
							.setId(id)
							.build();
					paymentEventProducer.publish(event);
				});
			}
		}
	}
}
