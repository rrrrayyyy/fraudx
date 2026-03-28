package com.example.payment.domain;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

@Component
public class AlertStore {
    private final ConcurrentHashMap<String, AtomicInteger> cardAlerts = new ConcurrentHashMap<>();
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    public void recordAlert(String cardId, long latencyMs) {
        cardAlerts.computeIfAbsent(cardId, k -> new AtomicInteger()).incrementAndGet();
        latencies.add(latencyMs);
    }

    public int getCount(String cardId) {
        var counter = cardAlerts.get(cardId);
        return counter != null ? counter.get() : 0;
    }

    public int totalAlerts() {
        return cardAlerts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public Set<String> cardIds() {
        return cardAlerts.keySet();
    }

    public List<Long> getLatencies() {
        return latencies;
    }
}
