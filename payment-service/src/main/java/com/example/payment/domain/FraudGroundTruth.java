package com.example.payment.domain;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class FraudGroundTruth {
    private final ConcurrentHashMap<String, Instant> map = new ConcurrentHashMap<>();

    public void put(String batchId, Instant completionTime) {
        map.put(batchId, completionTime);
    }

    public ConcurrentHashMap<String, Instant> getAll() {
        return map;
    }

    public int size() {
        return map.size();
    }
}
