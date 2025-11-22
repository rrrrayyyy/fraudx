package com.example.fraud_detection_service.adapter;

import java.util.HashMap;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import com.example.payment.Payment.PaymentEventValue;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.fetch-min-size}")
    private int fetchMinSize;

    @Value("${spring.kafka.consumer.max-partition-fetch-bytes}")
    private int maxPartitionFetchBytes;

    @Value("${spring.kafka.consumer.enable-auto-commit}")
    private boolean enableAutoCommit;

    @Value("${spring.kafka.consumer.concurrency}")
    private int concurrency;

    @Value("${spring.kafka.consumer.max-poll-records}")
    private int maxPollRecords;

    @Bean
    public ConsumerFactory<String, PaymentEventValue> protobufConsumerFactory() {
        var props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinSize);
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxPartitionFetchBytes);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, KafkaProtobufDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaProtobufDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEventValue> protobufConcurrentKafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentEventValue> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentEventValue>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
