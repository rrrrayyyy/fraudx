package com.example.payment.domain;

import java.util.concurrent.atomic.*;

import org.springframework.stereotype.Component;

@Component
public class ProducerStats {
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong durationNs = new AtomicLong();

    public void record(int count, long durationNs) {
        this.count.set(count);
        this.durationNs.set(durationNs);
    }

    public int count() {
        return count.get();
    }

    public long durationNs() {
        return durationNs.get();
    }

    public int rps() {
        long ns = durationNs.get();
        return ns > 0 ? (int) ((long) 1e9 * count.get() / ns) : 0;
    }
}
