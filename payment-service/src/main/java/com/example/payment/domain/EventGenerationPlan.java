package com.example.payment.domain;

import java.time.Duration;
import java.util.Random;

public record EventGenerationPlan(int n, int threshold, Duration duration, Random random, int[] cardEvents) {
	// 7 days of simulated card activity at 1 event per duration interval.
	// Normal events are spaced by duration (1min), so 1 day = 1440 events.
	// 50K/day would span 35 days of simulated time — unrealistic for a single card.
	private static final int SIMULATION_DAYS = 7;
	private static final double FRAUD_PROBABILITY = 0.0005;

	public EventGenerationPlan(int n, int threshold, Duration duration, Random random) {
		this(n, threshold, duration, random,
				distributeEvents(n, threshold, duration, random));
	}

	// Randomly distribute N events across cards.
	// Each card gets Uniform[threshold, maxEventsPerCard] events, capped so
	// remaining cards can each receive at least threshold events.
	// Last card absorbs the remainder to guarantee total = N.
	private static int[] distributeEvents(int n, int threshold, Duration duration, Random random) {
		int maxEventsPerCard = (int) (Duration.ofDays(SIMULATION_DAYS).toNanos() / duration.toNanos());
		int totalCards = Math.max(1, 2 * n / (threshold + maxEventsPerCard));
		var events = new int[totalCards];
		int remainingEvents = n;
		for (int i = 0; i < totalCards - 1; i++) {
			int remainingCards = totalCards - i;
			int cap = Math.min(maxEventsPerCard,
					remainingEvents - (remainingCards - 1) * threshold);
			events[i] = threshold + random.nextInt(cap - threshold + 1);
			remainingEvents -= events[i];
		}
		events[totalCards - 1] = remainingEvents;
		return events;
	}

	public int totalCards() {
		return cardEvents.length;
	}

	public int eventsForCard(int cardIdx) {
		return cardEvents[cardIdx];
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
