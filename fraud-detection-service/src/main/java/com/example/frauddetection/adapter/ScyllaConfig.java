package com.example.frauddetection.adapter;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

import com.datastax.oss.driver.api.core.CqlSession;

@Configuration
public class ScyllaConfig {
    @Value("${scylla.contact-points}")
    private String contactPoints;

    @Value("${scylla.local-datacenter}")
    private String localDatacenter;

    @Value("${scylla.keyspace}")
    private String keyspace;

    @Bean
    public CqlSession cqlSession() {
        var endpoints = Arrays.stream(contactPoints.split(","))
                .map(cp -> {
                    var parts = cp.trim().split(":");
                    return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                }).collect(Collectors.toList());

        return CqlSession.builder()
                .addContactPoints(endpoints)
                .withLocalDatacenter(localDatacenter)
                .withKeyspace(keyspace)
                .build();
    }
}
