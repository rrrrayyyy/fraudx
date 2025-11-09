package com.example.payment_service.adapter;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

import com.example.payment.Payment.PaymentEventValue;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaStarter {
	private final KafkaTopicConfig topicConfig;
	private final KafkaTopicCreator topicCreator;

	public KafkaStarter(KafkaTopicConfig topicConfig, KafkaTopicCreator topicCreator) {
		this.topicConfig = topicConfig;
		this.topicCreator = topicCreator;
	}

	@Bean
	public ApplicationRunner kafkaStarterRunner(KafkaTemplate<String, PaymentEventValue> kafkaTemplate) {
		return args -> {
			try {
				for (var key : topicConfig.getTopicConfig().keySet()) {
					var setting = topicConfig.getTopicConfig().get(key);
					var t = setting.getTopicName();
					if (t == null) {
						System.out.println("😈 Unexpected topic key specified: " + key);
						continue;
					}
					topicCreator.createTopic(t, setting.getPartitions(), setting.getReplicationFactor());
					kafkaTemplate.partitionsFor(t);
					System.out.println("✅ Kafka connection successful");
				}
			} catch (Exception e) {
				System.err.println("❌ Kafka connection failed: " + e.getMessage());
			}
		};
	}
}
