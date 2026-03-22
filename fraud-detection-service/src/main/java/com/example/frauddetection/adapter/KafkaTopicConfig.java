package com.example.frauddetection.adapter;

import java.util.*;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;

enum TopicKey {
    FRAUD_ALERTS;

    public String getKey() {
        return this.name().toLowerCase().replace('_', '-');
    }
}

record Topic(String name, int partitions, short replicationFactor) {
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

    public Topic getTopic(TopicKey key) {
        return Optional.ofNullable(topics.get(key.getKey()))
                .orElseThrow(() -> new IllegalStateException("Topic config missing for: " + key));
    }

    @Bean
    public NewTopic fraudAlertsTopic() {
        var t = getTopic(TopicKey.FRAUD_ALERTS);
        return TopicBuilder.name(t.name())
                .partitions(t.partitions())
                .replicas(t.replicationFactor())
                .build();
    }
}
