package com.example.payment_service.adapter;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

import com.example.payment.Payment.PaymentEventValue;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaStarter {
    private final KafkaTopics kafkaTopics;
    private final KafkaTopicCreator topicCreator;

    public KafkaStarter(KafkaTopics kafkaTopics, KafkaTopicCreator topicCreator) {
        this.kafkaTopics = kafkaTopics;
        this.topicCreator = topicCreator;
    }

    @Bean
    public ApplicationRunner kafkaStarterRunner(KafkaTemplate<String, PaymentEventValue> kafkaTemplate) {
        return args -> {
            try {
                var paymentTopic = kafkaTopics.get(KafkaTopic.PAYMENT.key);
                topicCreator.createTopic(paymentTopic, 4, (short) 2); // partition, replication factor
                kafkaTemplate.partitionsFor(paymentTopic);
                System.out.println("✅ Kafka connection successful");
            } catch (Exception e) {
                System.err.println("❌ Kafka connection failed: " + e.getMessage());
            }
        };
    }
}
