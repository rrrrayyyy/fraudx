package com.example.payment.domain;

import java.time.Instant;
import java.util.*;

import com.example.proto.PaymentMethod;
import com.github.f4b6a3.uuid.alt.GUID;

public record EventGenerationPlan(int n, int cards, Random random, CardEntry[] cardPool,
		Map<String, Instant> lastTimestamps) {

	public record CardEntry(String cardId, String userId, PaymentMethod paymentMethod) {
	}

	public EventGenerationPlan(int n, int cards, Random random) {
		this(n, cards, random, allocateCardPool(cards));
	}

	private EventGenerationPlan(int n, int cards, Random random, CardEntry[] cardPool) {
		this(n, cards, random, cardPool, initLastTimestamps(cardPool));
	}

	public CardEntry randomCard() {
		return cardPool[random.nextInt(cards)];
	}

	public Instant nextTimestamp(String cardId, long jitterMinNanos, long jitterMaxNanos) {
		var ts = lastTimestamps.get(cardId)
				.plusNanos(jitterMinNanos + random.nextLong(jitterMaxNanos - jitterMinNanos));
		lastTimestamps.put(cardId, ts);
		return ts;
	}

	public Instant[] burstTimestamps(String cardId, int burstMax, int remaining,
			long jitterMinNanos, long jitterMaxNanos, long durationNanos) {
		int burstSize = Math.min(2 + random.nextInt(burstMax - 1), remaining);
		var burstStart = lastTimestamps.get(cardId)
				.plusNanos(jitterMinNanos + random.nextLong(jitterMaxNanos - jitterMinNanos));
		var timestamps = new Instant[burstSize];
		for (int j = 0; j < burstSize; j++) {
			timestamps[j] = burstStart.plusNanos(random.nextLong(durationNanos));
		}
		Arrays.sort(timestamps);
		lastTimestamps.put(cardId, timestamps[burstSize - 1]);
		return timestamps;
	}

	private static CardEntry[] allocateCardPool(int cards) {
		var pool = new CardEntry[cards];
		for (int i = 0; i < cards; i++) {
			var cardId = GUID.v4().toString();
			var userId = GUID.v4().toString();
			var paymentMethod = PaymentMethod.newBuilder()
					.setId(GUID.v4().toString())
					.setType(PaymentMethod.Type.TYPE_CARD)
					.setCardId(cardId)
					.build();
			pool[i] = new CardEntry(cardId, userId, paymentMethod);
		}
		return pool;
	}

	private static Map<String, Instant> initLastTimestamps(CardEntry[] cardPool) {
		var now = Instant.now();
		var map = new HashMap<String, Instant>(cardPool.length);
		for (var card : cardPool) {
			map.put(card.cardId(), now);
		}
		return map;
	}
}
