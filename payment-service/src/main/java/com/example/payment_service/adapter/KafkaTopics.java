package com.example.payment_service.adapter;

import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kafka.topic")
public class KafkaTopics {
    private Map<String, String> topics = Collections.emptyMap();

    public Map<String, String> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, String> topics) {
        this.topics = topics;
    }

    public String get(String key) {
        var t = topics.get(key);
        if (t == null || t.isEmpty()) {
            throw new IllegalArgumentException("Kafka topic not found for key: " + key);
        }
        return t;
    }
}
