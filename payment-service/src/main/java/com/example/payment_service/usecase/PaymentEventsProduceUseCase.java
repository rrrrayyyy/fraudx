package com.example.payment_service.usecase;

import java.time.*;
import java.util.concurrent.*;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.github.f4b6a3.uuid.UuidCreator;

@Service
public class PaymentEventsProduceUseCase {
	private static final Logger log = LoggerFactory.getLogger("payment-service");
	private final PaymentEventProducer paymentEventProducer;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer) {
		this.paymentEventProducer = paymentEventProducer;
	}

	public void run(boolean logging, int n) {
		var startTime = Instant.now();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				executor.submit(() -> {
					var id = UuidCreator.getTimeOrderedEpoch().toString();
					var event = PaymentEventValue.newBuilder()
							.setId(id)
							.build();
					paymentEventProducer.publish(event);
					if (logging && log.isInfoEnabled()) {
						log.info("✅ Published payment event: {}", event);
					}
				});
			}
		} finally {
			var ms = Duration.between(startTime, Instant.now()).toMillis();
			log.info("🚀 Producer average RPS: " + (int) ((double) n / ms * 1000));
		}
	}
}
