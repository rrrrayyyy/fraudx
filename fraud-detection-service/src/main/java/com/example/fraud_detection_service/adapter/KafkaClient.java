package com.example.fraud_detection_service.adapter;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.proto.Event.PaymentEventValue;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final Deserializer<PaymentEventValue> deserializer;

    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient() {
        deserializer = new KafkaProtobufDeserializer<>(PaymentEventValue.parser());
    }

    @KafkaListener(topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true", containerFactory = "protobufConcurrentKafkaListenerContainerFactory")
    public void process(List<ConsumerRecord<byte[], byte[]>> records) {
        var now = System.nanoTime();
        startTime.compareAndSet(0, now);
        counter.add(records.size());
        var last = endTime.longValue();
        endTime.compareAndSet(last, now);
        for (var record : records) {
            try {
                // execute some logic
                deserializer.deserialize(null, record.value());
            } catch (RejectedExecutionException e) {
                log.error("❌ Executor queue full, dropping record: {}", record);
            } catch (Exception e) {
                log.error("❌ Subscription failed: {}", e.getMessage());
            } finally {
                if (log.isDebugEnabled()) {
                    log.debug("✅ Subscribed event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                            record.value());
                }
            }
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
