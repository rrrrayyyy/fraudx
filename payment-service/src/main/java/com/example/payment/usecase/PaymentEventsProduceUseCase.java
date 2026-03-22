package com.example.payment.usecase;

import java.time.Instant;
import java.util.Random;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.common.adapter.FraudRulesProperties;
import com.example.payment.domain.*;
import com.example.proto.PaymentMethod;
import com.example.proto.domain.PaymentEventFactory;
import com.github.f4b6a3.uuid.alt.GUID;

@Service
public class PaymentEventsProduceUseCase {
	private static final Logger log = LoggerFactory.getLogger(PaymentEventsProduceUseCase.class);
	private final PaymentEventProducer paymentEventProducer;
	private final FraudRulesProperties rulesProperties;
	private final FraudGroundTruth groundTruth;
	private final BlockedCards blockedCards;

	public PaymentEventsProduceUseCase(PaymentEventProducer paymentEventProducer,
			FraudRulesProperties rulesProperties, FraudGroundTruth groundTruth, BlockedCards blockedCards) {
		this.paymentEventProducer = paymentEventProducer;
		this.rulesProperties = rulesProperties;
		this.groundTruth = groundTruth;
		this.blockedCards = blockedCards;
	}

	public void run(int n) {
		var startTime = System.nanoTime();
		var rule = rulesProperties.transactionFrequency().targetAttributes().get("card-id");

		if (rule == null || !rule.enabled()) {
			for (int i = 0; i < n; i++) {
				var key = PaymentEventFactory.generateKey(GUID.v4().toString());
				var value = PaymentEventFactory.generateValue(GUID.v4().toString());
				paymentEventProducer.publish(key, value);
			}
		} else {
			var plan = new EventGenerationPlan(n, rule.threshold(), rule.duration(), new Random());

			for (int cardIdx = 0; cardIdx < plan.totalCards(); cardIdx++) {
				var cardId = GUID.v4().toString();
				var userId = GUID.v4().toString();
				var paymentMethod = PaymentMethod.newBuilder()
						.setId(GUID.v4().toString())
						.setType(PaymentMethod.Type.TYPE_CARD)
						.setCardId(cardId)
						.build();
				var cardBaseTime = Instant.now();

				for (int batchIdx = 0; batchIdx < plan.batchCount(cardIdx); batchIdx++) {
					if (blockedCards.contains(cardId)) {
						break;
					}

					var batchId = GUID.v4().toString();
					int batchSize = plan.batchSize(cardIdx, batchIdx);
					boolean isFraud = plan.isFraud(batchSize);
					var batchBase = cardBaseTime.plusNanos(plan.batchDurationNanos() * batchIdx);

					for (int eventIdx = 0; eventIdx < batchSize; eventIdx++) {
						Instant processedAt;
						if (isFraud) {
							processedAt = batchBase.plusNanos(
									(long) (plan.random().nextDouble() * plan.duration().toNanos()));
						} else {
							processedAt = batchBase.plus(plan.duration().multipliedBy(eventIdx));
						}
						var key = PaymentEventFactory.generateKey(GUID.v4().toString());
						var value = PaymentEventFactory.generateValue(userId, paymentMethod, processedAt, batchId);
						paymentEventProducer.publish(key, value);
					}

					if (isFraud) {
						groundTruth.put(batchId, Instant.now());
					}
				}
			}
		}

		var ns = System.nanoTime() - startTime;
		log.info("🚀 Producer average RPS: {} with requests: {}, duration(ms): {}, ground truth: {}",
				(int) ((long) 1e9 * n / ns), n, ns / (long) 1e6, groundTruth.size());
	}
}
