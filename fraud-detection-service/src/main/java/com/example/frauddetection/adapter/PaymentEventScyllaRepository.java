package com.example.frauddetection.adapter;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.*;
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
    private final int detectPageSize;
    private PreparedStatement insertStmt;
    private PreparedStatement detectStmt;

    public PaymentEventScyllaRepository(CqlSession session, RetryPolicy retryPolicy,
            KafkaProperties kafkaProperties) {
        this.session = session;
        this.retryPolicy = retryPolicy;
        this.detectPageSize = kafkaProperties.getConsumer().getMaxPollRecords();
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
                        SimpleStatement.newInstance(generateInsertCql())
                                .setIdempotent(true)
                                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM));
                this.detectStmt = session.prepare(
                        SimpleStatement.newInstance(
                                "SELECT processed_at FROM " + TABLE_NAME
                                        + " WHERE card_id = ? AND processed_at >= ? AND processed_at <= ?")
                                .setPageSize(detectPageSize)
                                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM));
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
                    event.cardId(), event.processedAt(), event.transactionId(), event.createdAt()))
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
    public List<DetectionResult> detectFraud(List<PaymentEvent> events, int threshold, Duration duration) {
        // Step 1: Per-card min/max processedAt
        var ranges = new HashMap<String, Instant[]>();
        var triggerCreatedAts = new HashMap<String, Instant>();
        for (var event : events) {
            ranges.merge(event.cardId(),
                    new Instant[] { event.processedAt(), event.processedAt() },
                    (a, b) -> new Instant[] {
                            a[0].isBefore(b[0]) ? a[0] : b[0],
                            a[1].isAfter(b[1]) ? a[1] : b[1]
                    });
            triggerCreatedAts.putIfAbsent(event.cardId(), event.createdAt());
        }

        // Step 2: Async range query per card
        record CardQuery(String cardId, Instant createdAt, CompletableFuture<AsyncResultSet> future) {
        }
        var queries = new ArrayList<CardQuery>(ranges.size());
        for (var entry : ranges.entrySet()) {
            var cardId = entry.getKey();
            var queryStart = entry.getValue()[0].minus(duration);
            var queryEnd = entry.getValue()[1];
            queries.add(new CardQuery(cardId, triggerCreatedAts.get(cardId),
                    session.executeAsync(detectStmt.bind(cardId, queryStart, queryEnd))
                            .toCompletableFuture()));
        }

        // Step 3: Sliding window on results
        var results = new ArrayList<DetectionResult>();
        for (var q : queries) {
            try {
                var rs = q.future().join();
                var timestamps = new ArrayList<Instant>();
                for (var row : rs.currentPage()) {
                    timestamps.add(row.getInstant("processed_at"));
                }
                // sorted DESC by clustering key, exhaustive (no skip)
                for (int i = 0; i <= timestamps.size() - threshold; i++) {
                    if (Duration.between(timestamps.get(i + threshold - 1), timestamps.get(i))
                            .compareTo(duration) <= 0) {
                        results.add(new DetectionResult(q.cardId(), timestamps.get(i), q.createdAt()));
                    }
                }
            } catch (Exception e) {
                log.warn("Detection query failed for card {}: {}", q.cardId(), e.getMessage());
            }
        }
        return results;
    }

    private static String generateInsertCql() {
        return QueryBuilder.insertInto(TABLE_NAME)
                .value("card_id", QueryBuilder.bindMarker())
                .value("processed_at", QueryBuilder.bindMarker())
                .value("transaction_id", QueryBuilder.bindMarker())
                .value("created_at", QueryBuilder.bindMarker())
                .asCql();
    }

    private static String createTable() {
        return SchemaBuilder.createTable(TABLE_NAME)
                .ifNotExists()
                .withPartitionKey("card_id", DataTypes.TEXT)
                .withClusteringColumn("processed_at", DataTypes.TIMESTAMP)
                .withClusteringColumn("transaction_id", DataTypes.TEXT)
                .withColumn("created_at", DataTypes.TIMESTAMP)
                .withClusteringOrder("processed_at", ClusteringOrder.DESC)
                .withCompaction(SchemaBuilder.timeWindowCompactionStrategy())
                .asCql();
    }
}
