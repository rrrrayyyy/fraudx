package com.example.payment_service.adapter;

import java.util.HashMap;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.*;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaConfig {
	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@Value("${spring.kafka.producer.compression-type}")
	private String compressionType;

	@Value("${spring.kafka.producer.batch-size}")
	private int batchSize;

	@Value("${spring.kafka.producer.linger-ms}")
	private long lingerMs;

	@Value("${spring.kafka.producer.acks}")
	private String acks;

	@Bean
	public ProducerFactory<byte[], byte[]> protobufProducerFactory() {
		var props = new HashMap<String, Object>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
		props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
		props.put(ProducerConfig.ACKS_CONFIG, acks);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		return new DefaultKafkaProducerFactory<>(props);
	}

	@Bean
	public KafkaTemplate<byte[], byte[]> protobufKafkaTemplate() {
		return new KafkaTemplate<>(protobufProducerFactory());
	}
}
