package com.example.fraud_detection_service.adapter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger("fraud-detection-service");
    private final ExecutorService executor;
    private final AtomicReference<Long> startTime = new AtomicReference<>();
    private long endTime;

    @Value("${n:0}")
    private int n;

    public KafkaClient(ExecutorService virtualThreadExecutor) {
        executor = virtualThreadExecutor;
    }

    @KafkaListener(topics = "payment-events", concurrency = "3")
    public void process(ConsumerRecord<String, String> record) {
        startTime.compareAndSet(null, System.nanoTime());
        endTime = System.nanoTime();
        executor.submit(() -> subscribe(record));
    }

    private void subscribe(ConsumerRecord<String, String> record) {
        try {
            // execute some logic
            log.info("✅ Subscribed event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                    record.value());
        } catch (Exception e) {
            log.error("❌ Error processing record: {}", e.getMessage(), e);
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

        if (startTime.get() != null) {
            double ms = (endTime - startTime.get()) / 1_000_000;
            log.info("🚀 Consumer average RPS: " + (double) n / ms * 1000);
        }
    }
}
