package com.example.fraud_detection_service.adapter;

import java.util.concurrent.ExecutorService;
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

    private final AtomicReference<Long> startTime = new AtomicReference<>();
    private long endTime;
    private LongAdder counter = new LongAdder();

    public KafkaClient(ExecutorService virtualThreadExecutor) {
        executor = virtualThreadExecutor;
    }

    @KafkaListener(topics = "payment-events", concurrency = "${kafka.consumer.concurrency}")
    public void process(ConsumerRecord<String, PaymentEventValue> record) {
        executor.submit(() -> subscribe(record));
    }

    private void subscribe(ConsumerRecord<String, PaymentEventValue> record) {
        startTime.compareAndSet(null, System.nanoTime());
        endTime = System.nanoTime();
        counter.increment();
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
        if (startTime.get() != null) {
            long ns = endTime - startTime.get();
            var count = counter.sum();
            log.info("🚀 Consumer average RPS: {} with requests: {}, duration(ms): {}",
                    (int) ((long) 1e9 * count / ns), count, ns / (long) 1e6);
        }
    }
}
