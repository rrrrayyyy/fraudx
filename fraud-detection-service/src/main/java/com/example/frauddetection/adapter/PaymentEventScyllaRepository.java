package com.example.frauddetection.adapter;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.slf4j.*;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.querybuilder.*;
import com.example.frauddetection.domain.*;
import com.example.frauddetection.usecase.PaymentEventRepository;

import jakarta.annotation.PostConstruct;

@Repository
public class PaymentEventScyllaRepository implements PaymentEventRepository {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventScyllaRepository.class);
    private static final String TABLE_NAME = "payment_events_by_card";
    private static final Duration DDL_TIMEOUT = Duration.ofSeconds(30);

    private final CqlSession session;
    private final RetryPolicy retryPolicy;
    private PreparedStatement insertStmt;
    private PreparedStatement detectStmt;

    public PaymentEventScyllaRepository(CqlSession session, RetryPolicy retryPolicy) {
        this.session = session;
        this.retryPolicy = retryPolicy;
    }

    @PostConstruct
    public void init() {
        Exception lastException = null;
        for (int retry = 0; retry < retryPolicy.maxRetries(); retry++) {
            if (retry > 0) {
                retryPolicy.backoff(retry - 1);
                log.warn("♻️ Retrying schema initialization (retry {}/{})",
                        retry, retryPolicy.maxRetries());
            }
            try {
                session.execute(SimpleStatement.newInstance(createTable())
                        .setTimeout(DDL_TIMEOUT));
                log.info("✅ Table {} is created", TABLE_NAME);
                this.insertStmt = session.prepare(
                        SimpleStatement.newInstance(generateInsertCql()).setIdempotent(true));
                this.detectStmt = session.prepare(
                        "SELECT processed_at, batch_id FROM " + TABLE_NAME
                                + " WHERE card_id = ? ORDER BY processed_at DESC LIMIT ?");
                log.info("✅ Prepared statements successfully");
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("⚠️ Schema initialization failed: {}", e.getMessage());
            }
        }
        log.error("❌ Schema initialization failed after {} retries", retryPolicy.maxRetries());
        throw new RuntimeException(lastException);
    }

    @Override
    public List<PaymentEvent> insertAll(List<PaymentEvent> events) {
        var futures = new CompletableFuture<?>[events.size()];
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            futures[i] = session.executeAsync(insertStmt.bind(
                    event.cardId(), event.processedAt(), event.transactionId(), event.batchId()))
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

    @Override
    public List<DetectionResult> detectFraud(Set<String> cardIds, int threshold, Duration duration) {
        var futures = new HashMap<String, CompletableFuture<com.datastax.oss.driver.api.core.cql.AsyncResultSet>>();
        for (var cardId : cardIds) {
            futures.put(cardId, session.executeAsync(detectStmt.bind(cardId, threshold))
                    .toCompletableFuture());
        }
        var results = new ArrayList<DetectionResult>();
        for (var entry : futures.entrySet()) {
            try {
                var rs = entry.getValue().join();
                var rows = new ArrayList<Row>();
                for (var row : rs.currentPage()) {
                    rows.add(row);
                }
                if (rows.size() == threshold) {
                    var newest = rows.getFirst().getInstant("processed_at");
                    var oldest = rows.getLast().getInstant("processed_at");
                    if (Duration.between(oldest, newest).compareTo(duration) <= 0) {
                        results.add(new DetectionResult(entry.getKey(), rows.getFirst().getString("batch_id")));
                    }
                }
            } catch (Exception e) {
                log.warn("Detection query failed for card {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return results;
    }

    private static String generateInsertCql() {
        return QueryBuilder.insertInto(TABLE_NAME)
                .value("card_id", QueryBuilder.bindMarker())
                .value("processed_at", QueryBuilder.bindMarker())
                .value("transaction_id", QueryBuilder.bindMarker())
                .value("batch_id", QueryBuilder.bindMarker())
                .asCql();
    }

    private static String createTable() {
        return SchemaBuilder.createTable(TABLE_NAME)
                .ifNotExists()
                .withPartitionKey("card_id", DataTypes.TEXT)
                .withClusteringColumn("processed_at", DataTypes.TIMESTAMP)
                .withClusteringColumn("transaction_id", DataTypes.TEXT)
                .withColumn("batch_id", DataTypes.TEXT)
                .withClusteringOrder("processed_at", ClusteringOrder.DESC)
                .withCompaction(SchemaBuilder.timeWindowCompactionStrategy())
                .asCql();
    }
}
