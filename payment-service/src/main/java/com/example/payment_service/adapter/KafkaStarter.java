package com.example.payment_service.adapter;

import org.slf4j.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

import com.example.payment.Payment.PaymentEventValue;

@Configuration
@ConditionalOnProperty(value = "kafka.connect", havingValue = "true")
public class KafkaStarter {
	private static final Logger log = LoggerFactory.getLogger("payment-service");
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
					var topic = topicConfig.getTopicConfig().get(key);
					if (topic == null) {
						log.warn("😈 Unexpected topic key: {}", key);
						continue;
					}
					topicCreator.createTopic(key, topic);
					kafkaTemplate.partitionsFor(key);
				}
			} catch (Exception e) {
				log.error("❌ Kafka connection failed: {}", e.getMessage());
			} finally {
				log.info("✅ Kafka connection succeeded");
			}
		};
	}
}
