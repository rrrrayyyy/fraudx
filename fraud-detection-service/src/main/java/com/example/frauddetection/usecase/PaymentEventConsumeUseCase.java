package com.example.frauddetection.usecase;

import java.util.List;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import com.example.frauddetection.domain.*;

@Service
public class PaymentEventConsumeUseCase {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumeUseCase.class);
    private final PaymentEventRepository repository;
    private final RetryPolicy retryPolicy;

    public PaymentEventConsumeUseCase(PaymentEventRepository repository, RetryPolicy retryPolicy) {
        this.repository = repository;
        this.retryPolicy = retryPolicy;
    }

    public void execute(List<PaymentEvent> events) {
        var failed = repository.insertAll(events);

        for (int retry = 0; 0 < failed.size() && retry < retryPolicy.maxRetries(); retry++) {
            retryPolicy.backoff(retry);
            log.warn("♻️ Retrying {} failed records (retry {}/{})",
                    failed.size(), retry + 1, retryPolicy.maxRetries());
            failed = repository.insertAll(failed);
        }
        if (0 < failed.size()) {
            log.error("❌ {} records failed after {} retries, skipping", failed.size(), retryPolicy.maxRetries());
        }
    }
}
