package com.example.payment_service.adapter;

import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaTopicConfig {
	private Map<String, Topic> topicConfig = new HashMap<>();

	public Map<String, Topic> getTopicConfig() {
		return topicConfig;
	}

	public void setTopicConfig(Map<String, Topic> topics) {
		this.topicConfig = topics;
	}
}

class Topic {
	private int partitions;
	private short replicationFactor;

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