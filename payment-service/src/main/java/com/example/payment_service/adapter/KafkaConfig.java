package com.example.payment_service.adapter;

import java.util.HashMap;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.*;

import com.example.payment.Payment.PaymentEventValue;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaConfig {
	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@Value("${spring.kafka.producer.acks}")
	private String acks;

	@Value("${spring.kafka.producer.batch-size}")
	private int batchSize;

	@Value("${spring.kafka.producer.buffer-memory}")
	private long bufferMemory;

	@Bean
	public ProducerFactory<String, PaymentEventValue> protobufProducerFactory() {
		var props = new HashMap<String, Object>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ProducerConfig.ACKS_CONFIG, acks);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
		return new DefaultKafkaProducerFactory<String, PaymentEventValue>(props);
	}

	@Bean
	public KafkaTemplate<String, PaymentEventValue> protobufKafkaTemplate() {
		return new KafkaTemplate<>(protobufProducerFactory());
	}
}
