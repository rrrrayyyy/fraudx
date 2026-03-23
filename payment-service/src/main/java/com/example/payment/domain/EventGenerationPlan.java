package com.example.payment.domain;

import java.time.Duration;
import java.util.Random;

public record EventGenerationPlan(int n, int threshold, Duration duration, Random random, int[] cardEvents) {
	private static final int BATCHES_PER_CARD = 200;
	private static final double FRAUD_PROBABILITY = 0.0005;

	public EventGenerationPlan(int n, int threshold, Duration duration, Random random) {
		this(n, threshold, duration, random, distributeEvents(n, threshold, random));
	}

	// Randomly distribute N events across cards.
	// Each card picks uniformly from [1, avg*2] where avg = remainingEvents/remainingCards.
	// avg is self-correcting: if a card takes more, subsequent avg shrinks, and vice versa.
	// Last card absorbs the remainder to guarantee total = N.
	private static int[] distributeEvents(int n, int threshold, Random random) {
		int totalCards = Math.max(1, n / (threshold * BATCHES_PER_CARD));
		var events = new int[totalCards];
		int remainingEvents = n;
		for (int i = 0; i < totalCards - 1; i++) {
			int remainingCards = totalCards - i;
			int avg = remainingEvents / remainingCards;
			events[i] = 1 + random.nextInt(avg * 2);
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
