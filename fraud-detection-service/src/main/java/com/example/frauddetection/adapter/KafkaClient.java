package com.example.frauddetection.adapter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.example.frauddetection.domain.*;
import com.example.proto.*;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final CqlSession cqlSession;
    private final PaymentRepository repository;
    private final RetryPolicy retryPolicy;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient(CqlSession session, PaymentRepository repository,
            KafkaConsumerProperties properties) {
        cqlSession = session;
        this.repository = repository;
        this.retryPolicy = new RetryPolicy(properties.maxRetries(), properties.maxBackoffMs());
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        startTime.compareAndSet(0, System.nanoTime());
        counter.add(records.size());

        var statements = new ArrayList<BoundStatement>(records.size());
        for (var record : records) {
            var event = new PaymentEvent(record.key(), record.value());
            statements.add(repository.getInsertStmt().bind(event.userId(), event.transactionId()));
        }
        var failed = insertAll(statements);

        for (int retry = 0; 0 < failed.size() && retry < retryPolicy.maxRetries(); retry++) {
            retryPolicy.backoff(retry);
            log.warn("♻️ Retrying {} failed records (retry {}/{})",
                    failed.size(), retry + 1, retryPolicy.maxRetries());
            failed = insertAll(failed);
        }
        if (0 < failed.size()) {
            log.error("❌ {} records failed after {} retries, skipping", failed.size(), retryPolicy.maxRetries());
        }

        endTime.accumulateAndGet(System.nanoTime(), Math::max);
    }

    private List<BoundStatement> insertAll(List<BoundStatement> statements) {
        var futures = new CompletableFuture<?>[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            futures[i] = cqlSession.executeAsync(statements.get(i)).toCompletableFuture()
                    .handle((result, ex) -> ex);
        }
        var failed = new ArrayList<BoundStatement>();
        for (int i = 0; i < futures.length; i++) {
            if (futures[i].join() != null) {
                failed.add(statements.get(i));
            }
        }
        return failed;
    }

    @PreDestroy
    public void onShutdown() {
        if (startTime.get() != 0) {
            long ns = endTime.get() - startTime.get();
            var count = counter.sum();
            log.info("🚀 Consumer average RPS: {} with requests: {}, duration(ms): {}",
                    (int) ((long) 1e9 * count / ns), count, ns / (long) 1e6);
        }
    }
}
