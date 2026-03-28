package com.example.payment.usecase;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.common.adapter.FraudRulesProperties;
import com.example.payment.adapter.BenchmarkProperties;
import com.example.payment.domain.*;
import com.example.proto.domain.PaymentEventFactory;
import com.github.f4b6a3.uuid.alt.GUID;

@Service
public class PaymentEventsProduceUseCase {
	private final PaymentEventProducer paymentEventProducer;
	private final BlockedCards blockedCards;
	private final ProducerStats producerStats;
	private final BenchmarkProperties benchmarkProperties;
	private final FraudRulesProperties rulesProperties;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer, BlockedCards blockedCards,
			ProducerStats producerStats, BenchmarkProperties benchmarkProperties,
			FraudRulesProperties rulesProperties) {
		this.paymentEventProducer = paymentEventProducer;
		this.blockedCards = blockedCards;
		this.producerStats = producerStats;
		this.benchmarkProperties = benchmarkProperties;
		this.rulesProperties = rulesProperties;
	}

	public void run(int n) {
		var rule = rulesProperties.transactionFrequency().targetAttributes().get("card-id");
		int threshold = rule != null ? rule.threshold() : 5;
		long durationNanos = (rule != null ? rule.duration() : Duration.ofMinutes(1)).toNanos();

		var plan = new EventGenerationPlan(n, benchmarkProperties.cards(), new java.util.Random());
		var fraudProbability = benchmarkProperties.fraudProbability();
		int burstMax = threshold * benchmarkProperties.burstMultiplier();
		var jitterMinNanos = benchmarkProperties.jitterMin().toNanos();
		var jitterMaxNanos = benchmarkProperties.jitterMax().toNanos();

		var startTime = System.nanoTime();

		for (int i = 0; i < n;) {
			var card = plan.randomCard();

			if (blockedCards.contains(card.cardId())) {
				continue;
			}

			int remaining = n - i;
			if (remaining >= 2 && plan.random().nextDouble() < fraudProbability) {
				var timestamps = plan.burstTimestamps(card.cardId(), burstMax, remaining,
						jitterMinNanos, jitterMaxNanos, durationNanos);
				for (var ts : timestamps) {
					var key = PaymentEventFactory.generateKey(GUID.v4().toString());
					var value = PaymentEventFactory.generateValue(card.userId(), card.paymentMethod(), ts);
					paymentEventProducer.publish(key, value);
				}
				i += timestamps.length;
			} else {
				var ts = plan.nextTimestamp(card.cardId(), jitterMinNanos, jitterMaxNanos);
				var key = PaymentEventFactory.generateKey(GUID.v4().toString());
				var value = PaymentEventFactory.generateValue(card.userId(), card.paymentMethod(), ts);
				paymentEventProducer.publish(key, value);
				i += 1;
			}
		}

		producerStats.record(n, System.nanoTime() - startTime);
	}
}
