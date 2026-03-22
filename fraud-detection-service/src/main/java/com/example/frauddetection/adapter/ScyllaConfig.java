package com.example.frauddetection.adapter;

import java.net.InetSocketAddress;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import com.datastax.oss.driver.api.core.CqlSession;

@Configuration
@EnableConfigurationProperties(ScyllaProperties.class)
public class ScyllaConfig {
    private final ScyllaProperties properties;

    public ScyllaConfig(ScyllaProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CqlSession cqlSession() {
        var endpoints = properties.contactPoints().stream()
                .map(String::trim)
                .map(cp -> {
                    var parts = cp.split(":");
                    var host = parts[0];
                    int port = (parts.length > 1) ? Integer.parseInt(parts[1]) : 9042;
                    return new InetSocketAddress(host, port);
                }).collect(Collectors.toList());

        try (var initSession = CqlSession.builder()
                .addContactPoints(endpoints)
                .withLocalDatacenter(properties.localDatacenter())
                .build()) {
            initSession.execute(
                    "CREATE KEYSPACE IF NOT EXISTS " + properties.keyspace()
                            + " WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}");
        }

        return CqlSession.builder()
                .addContactPoints(endpoints)
                .withLocalDatacenter(properties.localDatacenter())
                .withKeyspace(properties.keyspace())
                .build();
    }
}
