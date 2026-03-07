package com.example.frauddetection.adapter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.example.frauddetection.domain.PaymentEvent;
import com.example.proto.*;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final CqlSession cqlSession;
    private final PaymentRepository repository;

    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    private final MeterRegistry meterRegistry;
    private final Timer processingTimer;
    private final Counter eventCounter;

    @Value("${kafka.topics.payment.name}")
    private String paymentTopicName;

    public KafkaClient(CqlSession session, PaymentRepository repository, MeterRegistry meterRegistry) {
        cqlSession = session;
        this.repository = repository;
        this.meterRegistry = meterRegistry;

        this.processingTimer = Timer.builder("fraud.detection.processing.time")
                .description("Time taken to process a batch of payment events")
                .tag("service", "fraud-detection")
                .register(meterRegistry);

        this.eventCounter = Counter.builder("fraud.detection.events.total")
                .description("Total number of processed payment events")
                .tag("service", "fraud-detection")
                .register(meterRegistry);
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        var sample = Timer.start(meterRegistry);

        var now = System.nanoTime();
        startTime.compareAndSet(0, now);
        counter.add(records.size());

        var futures = records.stream().map(record -> {
            var event = new PaymentEvent(record.key(), record.value());
            var bound = repository.getInsertStmt().bind(event.userId(), event.transactionId()).setIdempotent(true);

            return cqlSession.executeAsync(bound).toCompletableFuture().handle((rs, ex) -> {
                if (ex != null) {
                    log.error("❌ Write failed partition={}, offset={}: {}",
                            record.partition(), record.offset(), ex.toString(), ex);
                    throw new RuntimeException("ScyllaDB write error", ex);
                }
                return rs;
            });
        }).toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).join();
            if (log.isDebugEnabled()) {
                log.debug("✅ Bulk insert succeeded for batch size: {}", records.size());
            }
            eventCounter.increment(records.size());
        } catch (Exception e) {
            log.error("❌ Batch processing failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            sample.stop(processingTimer);
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
