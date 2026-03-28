package com.example.payment.adapter;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.*;
import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.example.common.adapter.FraudRulesProperties;
import com.example.payment.domain.*;

import jakarta.annotation.PreDestroy;

@Component
public class ShutdownStatsReporter {
    private static final Logger log = LoggerFactory.getLogger(ShutdownStatsReporter.class);
    private final AlertStore alertStore;
    private final BlockedCards blockedCards;
    private final ProducerStats producerStats;
    private final FraudRulesProperties rulesProperties;
    private final ScyllaProperties scyllaProperties;

    public ShutdownStatsReporter(AlertStore alertStore, BlockedCards blockedCards, ProducerStats producerStats,
            FraudRulesProperties rulesProperties, ScyllaProperties scyllaProperties) {
        this.alertStore = alertStore;
        this.blockedCards = blockedCards;
        this.producerStats = producerStats;
        this.rulesProperties = rulesProperties;
        this.scyllaProperties = scyllaProperties;
    }

    @PreDestroy
    public void report() {
        var rule = rulesProperties.transactionFrequency().targetAttributes().get("card-id");
        int threshold = rule != null ? rule.threshold() : 5;
        Duration duration = rule != null ? rule.duration() : Duration.ofMinutes(1);

        var groundTruth = computeGroundTruth(threshold, duration);

        // Card-level confusion matrix
        var allCardIds = new HashSet<String>();
        allCardIds.addAll(groundTruth.keySet());
        allCardIds.addAll(alertStore.cardIds());

        int tp = 0, fp = 0, fn = 0;
        for (var cardId : allCardIds) {
            int truth = groundTruth.getOrDefault(cardId, 0);
            int alerts = alertStore.getCount(cardId);
            tp += Math.min(truth, alerts);
            fp += Math.max(0, alerts - truth);
            fn += Math.max(0, truth - alerts);
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;

        log.info("📊 === Shutdown Stats ===");
        log.info("📊 Producer: RPS={}, requests={}, duration(ms)={}",
                producerStats.rps(), producerStats.count(), producerStats.durationNs() / (long) 1e6);
        log.info("📊 Ground truth clusters: {}, Alerts received: {}, Blocked cards: {}",
                groundTruth.values().stream().mapToInt(Integer::intValue).sum(),
                alertStore.totalAlerts(), blockedCards.size());
        log.info("📊 Confusion matrix: TP={}, FP={}, FN={}", tp, fp, fn);
        log.info("📊 Precision: {}, Recall: {}", String.format("%.4f", precision), String.format("%.4f", recall));

        var latencies = new ArrayList<>(alertStore.getLatencies());
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            log.info("📊 Detection latency (ms): p50={}, p95={}, p99={}",
                    percentile(latencies, 50),
                    percentile(latencies, 95),
                    percentile(latencies, 99));
        }
    }

    private Map<String, Integer> computeGroundTruth(int threshold, Duration duration) {
        if (scyllaProperties == null || scyllaProperties.contactPoints() == null) {
            log.warn("⚠️ ScyllaDB not configured, skipping ground truth scan");
            return Map.of();
        }

        var endpoints = scyllaProperties.contactPoints().stream()
                .map(String::trim)
                .map(cp -> {
                    var parts = cp.split(":");
                    var host = parts[0];
                    int port = (parts.length > 1) ? Integer.parseInt(parts[1]) : 9042;
                    return new InetSocketAddress(host, port);
                }).collect(Collectors.toList());

        try (var session = CqlSession.builder()
                .addContactPoints(endpoints)
                .withLocalDatacenter(scyllaProperties.localDatacenter())
                .withKeyspace(scyllaProperties.keyspace())
                .build()) {
            return scanGroundTruth(session, threshold, duration);
        } catch (Exception e) {
            log.error("❌ Ground truth scan failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Integer> scanGroundTruth(CqlSession session, int threshold, Duration duration) {
        var cardIds = new ArrayList<String>();
        var rs = session.execute(SimpleStatement.newInstance(
                "SELECT DISTINCT card_id FROM payment_events_by_card")
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM));
        for (var row : rs) {
            cardIds.add(row.getString("card_id"));
        }
        log.info("📊 Ground truth scan: {} cards found", cardIds.size());

        var selectStmt = session.prepare(SimpleStatement.newInstance(
                "SELECT processed_at FROM payment_events_by_card WHERE card_id = ? ORDER BY processed_at DESC")
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM));

        var groundTruth = new HashMap<String, Integer>();
        for (var cardId : cardIds) {
            var events = new ArrayList<java.time.Instant>();
            var cardRs = session.execute(selectStmt.bind(cardId));
            for (var row : cardRs) {
                events.add(row.getInstant("processed_at"));
            }

            int clusterCount = 0;
            for (int i = 0; i <= events.size() - threshold; i++) {
                var newest = events.get(i);
                var oldest = events.get(i + threshold - 1);
                if (Duration.between(oldest, newest).compareTo(duration) <= 0) {
                    clusterCount++;
                }
            }

            if (clusterCount > 0) {
                groundTruth.put(cardId, clusterCount);
            }
        }
        return groundTruth;
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
