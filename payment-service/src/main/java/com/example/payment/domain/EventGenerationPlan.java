package com.example.payment.domain;

import java.time.Duration;
import java.util.Random;

public record EventGenerationPlan(int n, int threshold, Duration duration, Random random) {
	static final int EVENTS_PER_CARD = 1000;
	private static final double FRAUD_PROBABILITY = 0.0005;

	// ceil(n / EVENTS_PER_CARD). e.g. n=1005 -> 2 cards (1000 + 5)
	public int totalCards() {
		return (n + EVENTS_PER_CARD - 1) / EVENTS_PER_CARD;
	}

	// last card may have fewer than EVENTS_PER_CARD. e.g. card 1 of n=1005 -> 5
	public int eventsForCard(int cardIdx) {
		return Math.min(EVENTS_PER_CARD, n - cardIdx * EVENTS_PER_CARD);
	}

	// ceil(eventsForCard / threshold). last batch may be smaller than threshold
	public int batchCount(int cardIdx) {
		return (eventsForCard(cardIdx) + threshold - 1) / threshold;
	}

	// threshold for full batches, remainder for the last batch. e.g. 5 events,
	// threshold=3 -> [3, 2]
	public int batchSize(int cardIdx, int batchIdx) {
		return Math.min(threshold, eventsForCard(cardIdx) - batchIdx * threshold);
	}

	// true if this batch should contain fraudulent events (only full batches can be
	// fraud)
	public boolean isFraud(int batchSize) {
		return batchSize == threshold && random.nextDouble() < FRAUD_PROBABILITY;
	}

	// time range reserved per mini-batch. e.g. duration=1min, threshold=5 -> 5min
	public long batchDurationNanos() {
		return duration.toNanos() * threshold;
	}
}
