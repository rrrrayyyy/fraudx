package com.example.frauddetection.adapter;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.slf4j.*;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.querybuilder.*;
import com.example.frauddetection.domain.PaymentEvent;
import com.example.frauddetection.usecase.PaymentEventRepository;

import jakarta.annotation.PostConstruct;

@Repository
public class PaymentEventScyllaRepository implements PaymentEventRepository {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventScyllaRepository.class);
    private static final String TABLE_NAME = "payment_events_by_card";

    private final CqlSession session;
    private PreparedStatement insertStmt;

    public PaymentEventScyllaRepository(CqlSession session) {
        this.session = session;
    }

    @PostConstruct
    public void init() {
        try {
            session.execute(createTable());
            log.info("✅ Table {} is created", TABLE_NAME);
            this.insertStmt = session.prepare(
                    SimpleStatement.newInstance(generateInsertCql()).setIdempotent(true));
            log.info("✅ Prepared insert statement successfully");
        } catch (Exception e) {
            log.error("❌ Initializing tables or preparing statements failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<PaymentEvent> insertAll(List<PaymentEvent> events) {
        var futures = new CompletableFuture<?>[events.size()];
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            futures[i] = session.executeAsync(insertStmt.bind(
                    event.cardId(), event.createdAt(), event.transactionId()))
                    .toCompletableFuture()
                    .handle((result, ex) -> ex);
        }
        var failed = new ArrayList<PaymentEvent>();
        for (int i = 0; i < futures.length; i++) {
            if (futures[i].join() != null) {
                failed.add(events.get(i));
            }
        }
        return failed;
    }

    private static String generateInsertCql() {
        return QueryBuilder.insertInto(TABLE_NAME)
                .value("card_id", QueryBuilder.bindMarker())
                .value("created_at", QueryBuilder.bindMarker())
                .value("transaction_id", QueryBuilder.bindMarker())
                .asCql();
    }

    private static String createTable() {
        return SchemaBuilder.createTable(TABLE_NAME)
                .ifNotExists()
                .withPartitionKey("card_id", DataTypes.TEXT)
                .withClusteringColumn("created_at", DataTypes.TIMESTAMP)
                .withClusteringColumn("transaction_id", DataTypes.TEXT)
                .withClusteringOrder("created_at", ClusteringOrder.DESC)
                .withCompaction(SchemaBuilder.timeWindowCompactionStrategy())
                .asCql();
    }
}
