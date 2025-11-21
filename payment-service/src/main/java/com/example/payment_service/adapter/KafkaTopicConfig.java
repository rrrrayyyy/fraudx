package com.example.payment_service.adapter;

import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaTopicConfig {
	private Map<String, Topic> topics = new HashMap<>();

	public Map<String, Topic> getTopics() {
		return topics;
	}

	public void setTopics(Map<String, Topic> topics) {
		this.topics = topics;
	}
}

class Topic {
	private String name;
	private int partitions;
	private short replicationFactor;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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