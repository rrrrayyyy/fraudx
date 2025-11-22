package com.example.payment_service.adapter;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class KafkaTopicCreator {
	private static final Logger log = LoggerFactory.getLogger(KafkaTopicCreator.class);
	private final AdminClient adminClient;

	public KafkaTopicCreator(AdminClient adminClient) {
		this.adminClient = adminClient;
	}

	public void createTopic(Topic t) {
		var topic = new NewTopic(t.getName(), t.getPartitions(), t.getReplicationFactor());
		try {
			adminClient.createTopics(Collections.singleton(topic)).all().get();
			log.info("✅ Created topic: {}", t.getName());
		} catch (ExecutionException e) {
			if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
				log.warn("⚠️ Topic already exists: {}", t.getName());
			} else {
				throw new RuntimeException("❌ Failed to create topic: " + t.getName() + " by " + e.getMessage());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	public void waitForBrokers(int expectedCount, Duration timeout) throws Exception {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			var nodes = adminClient.describeCluster().nodes().get();
			if (nodes.size() >= expectedCount) {
				return;
			}
			Thread.sleep(500);
		}
		throw new IllegalStateException("Brokers did not reach required count: " + expectedCount);
	}
}
