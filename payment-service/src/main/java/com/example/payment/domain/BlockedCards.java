package com.example.payment.domain;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class BlockedCards {
    private final Set<String> set = ConcurrentHashMap.newKeySet();

    public void add(String cardId) {
        set.add(cardId);
    }

    public boolean contains(String cardId) {
        return set.contains(cardId);
    }

    public int size() {
        return set.size();
    }
}
