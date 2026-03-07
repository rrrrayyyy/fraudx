package com.example.frauddetection.adapter;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scylla")
public record ScyllaProperties(
        List<String> contactPoints,
        String localDatacenter,
        String keyspace) {
}
