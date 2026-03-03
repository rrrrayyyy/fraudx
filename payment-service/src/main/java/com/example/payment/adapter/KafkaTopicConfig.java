package com.example.payment.adapter;

import java.util.*;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;

enum TopicKey {
	PAYMENT;

	public String getKey() {
		return this.name().toLowerCase();
	}
}

@Configuration
@ConfigurationProperties(prefix = "kafka")
public class KafkaTopicConfig {
	private Map<String, Topic> topics = new HashMap<>();

	public Map<String, Topic> getTopics() {
		return topics;
	}

	public void setTopics(Map<String, Topic> topics) {
		this.topics = topics;
	}

	public Topic getPaymentTopic() {
		return topics.get(TopicKey.PAYMENT.getKey());
	}

	@Bean
	public NewTopic paymentTopic() {
		var t = getPaymentTopic();
		return TopicBuilder.name(t.getName())
				.partitions(t.getPartitions())
				.replicas(t.getReplicationFactor())
				.build();
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