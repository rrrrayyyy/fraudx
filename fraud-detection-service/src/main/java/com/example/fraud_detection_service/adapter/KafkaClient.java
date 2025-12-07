package com.example.fraud_detection_service.adapter;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Service;

import com.example.fraud_detection_service.domain.PaymentEvent;
import com.example.proto.Event.*;

import jakarta.annotation.*;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final Deserializer<PaymentEventKey> keyDeserializer;
    private final Deserializer<PaymentEventValue> valueDeserializer;
    private final KafkaListenerEndpointRegistry registry;
    private final AdminClient adminClient;

    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    @Value("${kafka.topics.payment.name}")
    private String paymentTopicName;

    public KafkaClient(KafkaListenerEndpointRegistry registry, AdminClient adminClient) {
        keyDeserializer = new KafkaProtobufDeserializer<>(PaymentEventKey.parser());
        valueDeserializer = new KafkaProtobufDeserializer<>(PaymentEventValue.parser());
        this.registry = registry;
        this.adminClient = adminClient;
    }

    @PostConstruct
    public void startIfTopicExists() {
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                while (true) {
                    var topics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
                    if (topics.contains(paymentTopicName)) {
                        log.info("✅ Topic confirmed: {}", paymentTopicName);
                        var listener = registry.getListenerContainer("paymentListener");
                        if (listener != null && !listener.isRunning()) {
                            listener.start();
                            log.info("✅ Listener startup succeeded");
                        }
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                log.error("❌ Failed to start listener: {}", e.getMessage());
            }
        });
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true", containerFactory = "protobufConcurrentKafkaListenerContainerFactory", autoStartup = "false")
    public void process(List<ConsumerRecord<byte[], byte[]>> records) {
        var now = System.nanoTime();
        startTime.compareAndSet(0, now);
        counter.add(records.size());
        var last = endTime.longValue();
        endTime.compareAndSet(last, now);
        for (var record : records) {
            try {
                // execute some logic
                var key = keyDeserializer.deserialize(null, record.value());
                var value = valueDeserializer.deserialize(null, record.value());
                var event = new PaymentEvent(key.getTransactionId(), value.getAccount().getUserId());
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
