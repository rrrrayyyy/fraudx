package com.example.frauddetection.domain;

public class RetryPolicy {
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long DEFAULT_MAX_BACKOFF_MS = 5000L;
    private static final long INITIAL_BACKOFF_MS = 50L;

    private final int maxRetries;
    private final long maxBackoffMs;

    public RetryPolicy(int maxRetries, long maxBackoffMs) {
        this.maxRetries = 0 < maxRetries ? maxRetries : DEFAULT_MAX_RETRIES;
        this.maxBackoffMs = 0 < maxBackoffMs ? maxBackoffMs : DEFAULT_MAX_BACKOFF_MS;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public void backoff(int retry) {
        long ms = Math.min(INITIAL_BACKOFF_MS * (1L << retry), maxBackoffMs);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
