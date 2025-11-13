package com.example.fraud_detection_service.adapter;

import java.util.concurrent.ExecutorService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private final ExecutorService executor;

    public KafkaClient(ExecutorService virtualThreadExecutor) {
        executor = virtualThreadExecutor;
    }

    @KafkaListener(topics = "payment-events", concurrency = "3")
    public void receive(ConsumerRecord<String, String> record) {
        executor.submit(() -> processRecord(record));
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            log.info("📩 Processing event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                    record.value());
            // execute some logic
            System.out.println("✅ event subscription succeeded");
        } catch (Exception e) {
            log.error("❌ Error processing record: {}", e.getMessage(), e);
        }
    }
}
