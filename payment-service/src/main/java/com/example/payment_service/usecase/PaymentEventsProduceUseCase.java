package com.example.payment_service.usecase;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.proto.Event.*;
import com.github.f4b6a3.uuid.UuidCreator;

@Service
public class PaymentEventsProduceUseCase {
	private static final Logger log = LoggerFactory.getLogger(PaymentEventsProduceUseCase.class);
	private final PaymentEventProducer paymentEventProducer;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer) {
		this.paymentEventProducer = paymentEventProducer;
	}

	public void run(boolean logging, int n) {
		var startTime = System.nanoTime();
		for (int i = 0; i < n; i++) {
			var transactionId = UuidCreator.getTimeOrderedEpoch().toString();
			var userId = UuidCreator.getTimeOrderedEpoch().toString();
			var key = PaymentEventKey.newBuilder()
					.setTransactionId(transactionId)
					.build();
			var value = PaymentEventValue.newBuilder()
					.setUserId(userId)
					.build();
			paymentEventProducer.publish(key, value);
			if (logging && log.isInfoEnabled()) {
				log.info("✅ Published payment event: {}", value);
			}
		}
		var ns = System.nanoTime() - startTime;
		log.info("🚀 Producer average RPS: {} with requests: {}, duration(ms): {}",
				(int) ((long) 1e9 * n / ns), n, ns / (long) 1e6);
	}
}
