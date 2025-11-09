package com.example.payment_service.usecase;

import java.util.concurrent.*;

import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.fasterxml.uuid.Generators;

@Service
public class PaymentEventsProduceUseCase {
	private final PaymentEventProducer paymentEventProducer;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer) {
		this.paymentEventProducer = paymentEventProducer;
	}

	public void run(int n, boolean isString) {
		System.out.println("PaymentEventsProduceUseCase.run() started with: " + n);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				int sequenceId = i;
				executor.submit(() -> {
					System.out.println("🧵 " + Thread.currentThread() + " processes " + sequenceId);
					var id = Generators.timeBasedEpochGenerator().generate().toString();
					if (isString) {
						paymentEventProducer.sendPaymentEvent(id);
					} else {
						var event = PaymentEventValue.newBuilder()
								.setId(id)
								.build();
						paymentEventProducer.sendPaymentEvent(event);
					}
				});
			}
		}
	}
}
