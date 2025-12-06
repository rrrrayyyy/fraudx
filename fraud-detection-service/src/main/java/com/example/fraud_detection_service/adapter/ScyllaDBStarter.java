package com.example.fraud_detection_service.adapter;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.CqlSession;

import jakarta.annotation.PostConstruct;

@Component
@ConditionalOnProperty(name = "scylladb.connect", havingValue = "true")
public class ScyllaDBStarter {
    private static final Logger log = LoggerFactory.getLogger(ScyllaDBStarter.class);
    private final CqlSession cqlSession;

    @Value("${scylladb.keyspace-name}")
    private String keyspace;

    @Value("${scylladb.replication-factor}")
    private int replicationFactor;

    public ScyllaDBStarter(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    @PostConstruct
    public void initialize() {
        createKeyspace();
        log.info("✅ Scylladb started successfully");
    }

    private void createKeyspace() {
        var cql = String.format(
                "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {'class': 'NetworkTopologyStrategy', 'replication_factor': %s}",
                keyspace, replicationFactor);
        cqlSession.execute(cql);
        cqlSession.execute("USE " + keyspace);
        log.info("✅ Keyspace: {} created with replication-factor: {}", keyspace, replicationFactor);
    }
}
