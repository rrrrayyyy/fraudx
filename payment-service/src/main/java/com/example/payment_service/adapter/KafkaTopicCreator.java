package com.example.payment_service.adapter;

import java.util.Collections;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class KafkaTopicCreator {
	private static final Logger log = LoggerFactory.getLogger("payment-service");
	private final AdminClient adminClient;

	public KafkaTopicCreator(AdminClient adminClient) {
		this.adminClient = adminClient;
	}

	public void createTopic(String topicName, int partitions, short replicationFactor) {
		var topic = new NewTopic(topicName, partitions, replicationFactor);
		try {
			adminClient.createTopics(Collections.singleton(topic)).all().get();
			log.info("✅ Created topic: {}", topicName);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
				log.warn("⚠️ Topic already exists: {}", topicName);
			} else {
				throw new RuntimeException("❌ Failed to create topic: " + topicName, e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}
}
