package com.example.fraud_detection_service.adapter;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.example.fraud_detection_service.domain.PaymentEvent;
import com.example.proto.Event.*;

import jakarta.annotation.*;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final Deserializer<PaymentEventKey> keyDeserializer;
    private final Deserializer<PaymentEventValue> valueDeserializer;
    private final CqlSession cqlSession;

    private PreparedStatement insertStmt;
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final LongAdder counter = new LongAdder();
    private final Semaphore inFlight;

    @Value("${kafka.topics.payment.name}")
    private String paymentTopicName;

    public KafkaClient(CqlSession session) {
        keyDeserializer = new KafkaProtobufDeserializer<>(PaymentEventKey.parser());
        valueDeserializer = new KafkaProtobufDeserializer<>(PaymentEventValue.parser());
        cqlSession = session;
        inFlight = new Semaphore(32768);
    }

    @PostConstruct
    public void initializeTablesAndPrepareStatements() {
        var event = new PaymentEvent();
        try {
            cqlSession.execute(event.getCreateTable());
            log.info("✅ Table {} is created", PaymentEvent.TABLE_NAME);
            insertStmt = cqlSession.prepare(event.getInsertInto());
            log.info("✅ Prepared insert statement successfully");
        } catch (Exception e) {
            log.error("❌ Initializing tables or preparing statements failed: {}", e.getMessage());
        }
    }

    @KafkaListener(id = "paymentListener", topics = "${kafka.topics.payment.name}", concurrency = "${spring.kafka.consumer.concurrency}", batch = "true")
    public void process(List<ConsumerRecord<byte[], byte[]>> records) {
        var now = System.nanoTime();
        startTime.compareAndSet(0, now);
        counter.add(records.size());
        var last = endTime.longValue();
        endTime.compareAndSet(last, now);
        // var futures = new
        // ArrayList<CompletableFuture<AsyncResultSet>>(records.size());
        for (var record : records) {
            try {
                var ok = inFlight.tryAcquire(1, 600, TimeUnit.SECONDS);
                if (!ok) {
                    log.error("❌ Couldn't acquire semaphore, dropping record: {}", record);
                    continue;
                }

                var key = keyDeserializer.deserialize(null, record.key());
                var value = valueDeserializer.deserialize(null, record.value());
                var event = new PaymentEvent(key, value);
                var bound = insertStmt.bind(event.transactionId.getValue(), event.userId.getValue());
                // futures.add(cqlSession.executeAsync(bound).toCompletableFuture());

                cqlSession.executeAsync(bound).toCompletableFuture().whenComplete((rs, ex) -> {
                    inFlight.release();
                    if (ex != null) {
                        log.error("❌ write failed partition={}, offset={} : {}", record.partition(),
                                record.offset(),
                                ex.toString());
                    }
                });
            } catch (RejectedExecutionException e) {
                log.error("❌ Executor queue full, dropping record: {}", record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ Interrupted while waiting for inFlight permit");
            } catch (Exception e) {
                log.error("❌ Subscription failed: {}", e.getMessage());
            } finally {
                if (log.isDebugEnabled()) {
                    log.debug("✅ Subscribed event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                            record.value());
                }
            }
        }
        // try {
        // CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        // log.info("✅ Bulk insert succeeded: {}/{}", futures.size(), records.size());
        // } catch (Exception e) {
        // try {
        // Thread.sleep(5000);
        // } catch (InterruptedException ie) {
        // Thread.currentThread().interrupt();
        // }
        // log.error("❌ Inserting records failed: {}", e.getMessage());
        // throw e;
        // }
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
