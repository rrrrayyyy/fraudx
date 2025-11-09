package com.example.payment_service.adapter;

import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaTopicConfig {
	private Map<String, TopicSetting> topicConfig = new HashMap<>();

	public Map<String, TopicSetting> getTopicConfig() {
		return topicConfig;
	}

	public void setTopicConfig(Map<String, TopicSetting> topics) {
		this.topicConfig = topics;
	}

	public String getTopicName(String key) {
		var t = topicConfig.get(key);
		if (t == null || t.getTopicName().isEmpty()) {
			throw new IllegalArgumentException("❌ Kafka topic not found for key: " + key);
		}
		return t.getTopicName();
	}

	public static class TopicSetting {
		private String topicName;
		private int partitions;
		private short replicationFactor;

		public String getTopicName() {
			return topicName;
		}

		public void setTopicName(String topicName) {
			this.topicName = topicName;
		}

		public int getPartitions() {
			return partitions;
		}

		public void setPartitions(int partitions) {
			this.partitions = partitions;
		}

		public short getReplicationFactor() {
			return replicationFactor;
		}

		public void setReplicationFactor(short replicationFactor) {
			this.replicationFactor = replicationFactor;
		}
	}
}

enum KafkaTopic {
	PAYMENT("payment");

	final String key;

	private KafkaTopic(String key) {
		this.key = key;
	}
}