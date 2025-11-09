package com.example.payment_service.usecase;

import java.util.concurrent.*;

import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.fasterxml.uuid.Generators;

@Service
public class PaymentEventsProduceUseCase {
	private final KafkaProducer kafkaProducer;

	public PaymentEventsProduceUseCase(KafkaProducer kafkaProducer) {
		this.kafkaProducer = kafkaProducer;
	}

	public void run(int n, boolean isString) {
		System.out.println("PaymentEventsProduceUseCase.run() started with: " + n);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				int sequenceId = i;
				executor.submit(() -> {
					System.out.println("🧵 " + Thread.currentThread() + " processes " + sequenceId);
					var v7 = Generators.timeBasedEpochGenerator().generate();
					if (isString) {
						kafkaProducer.sendStringPaymentEvent("string val: " + v7.toString());
					} else {
						var event = PaymentEventValue.newBuilder().setId(v7.toString()).build();
						kafkaProducer.sendPaymentEvent(event);
					}
					return null;
				});
			}
		}
	}
}
