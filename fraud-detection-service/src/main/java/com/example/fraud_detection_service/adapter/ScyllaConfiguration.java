package com.example.fraud_detection_service.adapter;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

@Configuration
public class ScyllaConfiguration {

    @Value("${spring.cassandra.contact-points}")
    private List<String> contactPoints;

    @Value("${spring.cassandra.local-datacenter}")
    private String datacenter;

    @Value("${spring.cassandra.keyspace-name}")
    private String keyspace;

    @Bean
    public CqlSession cqlSession() {
        return CqlSession.builder()
                .addContactPoints(contactPoints.stream().map(
                        c -> new InetSocketAddress(c, 9042)).collect(Collectors.toList()))
                .withKeyspace(keyspace)
                .withLocalDatacenter(datacenter)
                .withConfigLoader(DriverConfigLoader.fromClasspath("application.conf")).build();
    }
}
