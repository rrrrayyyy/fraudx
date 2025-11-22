package com.example.fraud_detection_service.adapter;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final ExecutorService executor;

    @Value("${logging:false}")
    private boolean logging;

    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @KafkaListener(topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<String, PaymentEventValue>> records) {
        var now = System.nanoTime();
        startTime.compareAndSet(0, now);
        counter.add(records.size());
        var last = endTime.longValue();
        endTime.compareAndSet(last, now);
        for (var record : records) {
            try {
                executor.submit(() -> subscribe(record));
            } catch (RejectedExecutionException e) {
                log.warn("⚠️ Executor queue full, dropping record: {}", record);
            } catch (Exception e) {
                log.warn("⚠️ Subscription failed: {}", e.getMessage());
            }
        }
    }

    private void subscribe(ConsumerRecord<String, PaymentEventValue> record) {
        try {
            // execute some logic
        } catch (Exception e) {
            log.error("❌ Error processing record: {}", e.getMessage(), e);
        } finally {
            if (logging && log.isInfoEnabled()) {
                log.info("✅ Subscribed event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                        record.value());
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (startTime.get() != 0) {
            long ns = endTime.get() - startTime.get();
            var count = counter.sum();
            log.info("🚀 Consumer average RPS: {} with requests: {}, duration(ms): {}",
                    (int) ((long) 1e9 * count / ns), count, ns / (long) 1e6);
        }
    }
}
