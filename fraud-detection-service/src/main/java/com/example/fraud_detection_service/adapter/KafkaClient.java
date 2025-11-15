package com.example.fraud_detection_service.adapter;

import java.util.concurrent.ExecutorService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaClient {
    private static final Logger log = LoggerFactory.getLogger("fraud-detection-service");
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
            // execute some logic
            log.info("✅ Processing event [partition={}, offset={}]: {}", record.partition(), record.offset(),
                    record.value());
        } catch (Exception e) {
            log.error("❌ Error processing record: {}", e.getMessage(), e);
        }
    }
}
