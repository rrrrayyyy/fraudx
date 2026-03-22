package com.example.frauddetection.adapter;

import java.util.*;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.frauddetection.domain.PaymentEvent;
import com.example.frauddetection.usecase.PaymentEventsConsumeUseCase;
import com.example.proto.*;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final PaymentEventsConsumeUseCase consumeUseCase;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient(PaymentEventsConsumeUseCase consumeUseCase) {
        this.consumeUseCase = consumeUseCase;
    }

    @KafkaListener(id = "fraud-detection-payment-events-handler", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        startTime.compareAndSet(0, System.nanoTime());
        counter.add(records.size());

        var events = new ArrayList<PaymentEvent>(records.size());
        for (var record : records) {
            if (record.key() == null || record.value() == null) {
                log.warn("⚠️ Skipping record with null key or value at offset={}, partition={}",
                        record.offset(), record.partition());
                continue;
            }
            events.add(new PaymentEvent(record.key(), record.value()));
        }
        consumeUseCase.execute(events);

        endTime.accumulateAndGet(System.nanoTime(), Math::max);
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
