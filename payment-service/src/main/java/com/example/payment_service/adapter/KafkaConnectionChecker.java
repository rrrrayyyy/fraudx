package com.example.payment_service.adapter;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaConnectionChecker {
    private final KafkaTopics kafkaTopics;

    public KafkaConnectionChecker(KafkaTopics kafkaTopics) {
        this.kafkaTopics = kafkaTopics;
    }

    @Bean
    public ApplicationRunner kafkaConnectionRunner(KafkaTemplate<String, String> kafkaTemplate) {
        return args -> {
            try {
                kafkaTemplate.partitionsFor(kafkaTopics.get("payment"));
                System.out.println("✅ Kafka connection successful");
            } catch (Exception e) {
                System.err.println("❌ Kafka connection fialed: " + e.getMessage());
            }
        };
    }
}
