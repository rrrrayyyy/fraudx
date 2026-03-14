package com.example.frauddetection.adapter;

import java.net.InetSocketAddress;
import java.util.stream.Collectors;

import org.slf4j.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import com.datastax.oss.driver.api.core.CqlSession;

@Configuration
@EnableConfigurationProperties(ScyllaProperties.class)
public class ScyllaConfig {
    private static final Logger log = LoggerFactory.getLogger(ScyllaConfig.class);
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

        var session = CqlSession.builder()
                .addContactPoints(endpoints)
                .withLocalDatacenter(properties.localDatacenter())
                .withKeyspace(properties.keyspace())
                .build();

        waitForPoolReady(session);
        return session;
    }

    private void waitForPoolReady(CqlSession session) {
        log.info("Pool warmup: waiting for ScyllaDB connections to stabilize...");
        log.info("Pool warmup: {} nodes discovered", session.getMetadata().getNodes().size());

        int prevTotal = 0;
        int stableCount = 0;
        for (int i = 0; i < 60; i++) { // max 30s
            int total = session.getMetadata().getNodes().values().stream()
                    .mapToInt(n -> n.getOpenConnections()).sum();
            if (total > 0 && total == prevTotal) {
                stableCount++;
                if (stableCount >= 4) {
                    break; // stable for 2s
                }
            } else {
                stableCount = 0;
            }
            prevTotal = total;
            if (i % 4 == 0) {
                log.info("Pool warmup: poll={}, totalConnections={}", i, total);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        session.getMetadata().getNodes().forEach((id, node) -> log.info("Pool ready: node={}, state={}, connections={}",
                node.getEndPoint(), node.getState(), node.getOpenConnections()));
    }
}
