package com.example.frauddetection.adapter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.example.frauddetection.domain.PaymentEvent;
import com.example.proto.*;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final CqlSession cqlSession;
    private final PaymentRepository repository;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient(CqlSession session, PaymentRepository repository) {
        cqlSession = session;
        this.repository = repository;
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        startTime.compareAndSet(0, System.nanoTime());
        counter.add(records.size());

        int size = records.size();
        var futures = new CompletableFuture[size];
        for (int i = 0; i < size; i++) {
            var record = records.get(i);
            var event = new PaymentEvent(record.key(), record.value());
            var bound = repository.getInsertStmt().bind(event.userId(), event.transactionId());
            futures[i] = cqlSession.executeAsync(bound).toCompletableFuture();
        }

        try {
            CompletableFuture.allOf(futures).join();
            if (log.isDebugEnabled()) {
                log.debug("✅ Bulk insert succeeded for batch size: {}", size);
            }
        } catch (Exception e) {
            log.error("❌ Batch processing failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            endTime.accumulateAndGet(System.nanoTime(), Math::max);
        }
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
