package com.example.payment_service.adapter;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.acks}")
    private String acks;

    @Value("${spring.kafka.batch-size}")
    private String batchSize;

    @Value("${spring.kafka.buffer-memory}")
    private String bufferMemory;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        var props = new HashMap<String, Object>();
        return new DefaultKafkaProducerFactory<>(props);
    }
}
