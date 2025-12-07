package com.example.fraud_detection_service.adapter;

import java.util.*;
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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
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
    private final CqlSession cqlSession;

    private PreparedStatement insertStmt;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();

    @Value("${kafka.topics.payment.name}")
    private String paymentTopicName;

    public KafkaClient(KafkaListenerEndpointRegistry registry, AdminClient adminClient, CqlSession session) {
        keyDeserializer = new KafkaProtobufDeserializer<>(PaymentEventKey.parser());
        valueDeserializer = new KafkaProtobufDeserializer<>(PaymentEventValue.parser());
        this.registry = registry;
        this.adminClient = adminClient;
        cqlSession = session;
    }

    @PostConstruct
    public void initializeTablesAndPrepareStatements() {
        var event = new PaymentEvent();
        try {
            cqlSession.execute(event.getCreateTable());
            log.info("✅ Table {} is created", PaymentEvent.TABLE_NAME);
        } catch (Exception e) {
            log.error("❌ Create table failed: {}", e.getMessage());
        }
        insertStmt = cqlSession.prepare(event.getInsertInto());
        log.info("✅ Prepared insert statement successfully");
    }

    @PostConstruct
    public void startIfTopicExists() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    var topics = adminClient.listTopics().names().get(1, TimeUnit.SECONDS);
                    if (topics.contains(paymentTopicName)) {
                        var listener = registry.getListenerContainer("paymentListener");
                        if (listener != null && !listener.isRunning()) {
                            listener.start();
                            log.info("✅ Listener startup succeeded");
                            break;
                        }
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    log.error("❌ Failed to start listener: {}", e.getMessage());
                }
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
        var futures = new ArrayList<CompletableFuture<AsyncResultSet>>(records.size());
        for (var record : records) {
            try {
                var key = keyDeserializer.deserialize(null, record.key());
                var value = valueDeserializer.deserialize(null, record.value());
                var event = new PaymentEvent(key, value);
                var bound = insertStmt.bind(event.transactionId.getValue(), event.userId.getValue());
                futures.add(cqlSession.executeAsync(bound).toCompletableFuture());
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
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("✅ Bulk insert succeeded: {}/{}", futures.size(), records.size());
        } catch (Exception e) {
            log.error("❌ Inserting records failed: {}", e.getMessage());
            throw e;
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
