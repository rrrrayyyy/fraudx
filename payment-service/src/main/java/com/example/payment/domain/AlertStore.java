package com.example.payment.domain;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class AlertStore {
    private final ConcurrentHashMap<String, Instant> map = new ConcurrentHashMap<>();

    public void put(String batchId, Instant arrivalTime) {
        map.put(batchId, arrivalTime);
    }

    public ConcurrentHashMap<String, Instant> getAll() {
        return map;
    }

    public int size() {
        return map.size();
    }
}
