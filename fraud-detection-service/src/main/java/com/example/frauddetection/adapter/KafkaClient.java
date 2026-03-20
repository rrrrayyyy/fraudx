package com.example.frauddetection.adapter;

import java.util.*;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.frauddetection.domain.*;
import com.example.frauddetection.usecase.*;
import com.example.proto.*;

import jakarta.annotation.PreDestroy;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final PaymentEventConsumeUseCase consumeUseCase;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    public KafkaClient(PaymentEventRepository repository, KafkaConsumerProperties properties) {
        this.consumeUseCase = new PaymentEventConsumeUseCase(
                repository, new RetryPolicy(properties.maxRetries(), properties.maxBackoffMs()));
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        startTime.compareAndSet(0, System.nanoTime());
        counter.add(records.size());

        var events = new ArrayList<PaymentEvent>(records.size());
        for (var record : records) {
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
