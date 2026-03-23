package com.example.payment.adapter;

import java.time.Duration;
import java.util.*;

import org.slf4j.*;
import org.springframework.stereotype.Component;

import com.example.payment.domain.*;

import jakarta.annotation.PreDestroy;

@Component
public class ShutdownStatsReporter {
    private static final Logger log = LoggerFactory.getLogger(ShutdownStatsReporter.class);
    private final FraudGroundTruth groundTruth;
    private final AlertStore alertStore;
    private final BlockedCards blockedCards;
    private final ProducerStats producerStats;

    public ShutdownStatsReporter(FraudGroundTruth groundTruth, AlertStore alertStore, BlockedCards blockedCards,
            ProducerStats producerStats) {
        this.groundTruth = groundTruth;
        this.alertStore = alertStore;
        this.blockedCards = blockedCards;
        this.producerStats = producerStats;
    }

    @PreDestroy
    public void report() {
        var truthMap = groundTruth.getAll();
        var alertMap = alertStore.getAll();

        int tp = 0;
        int fp = 0;
        int fn = 0;
        var latencies = new ArrayList<Long>();

        for (var entry : truthMap.entrySet()) {
            if (alertMap.containsKey(entry.getKey())) {
                tp++;
                long latencyMs = Duration.between(entry.getValue(), alertMap.get(entry.getKey())).toMillis();
                latencies.add(latencyMs);
            } else {
                fn++;
            }
        }

        for (var batchId : alertMap.keySet()) {
            if (!truthMap.containsKey(batchId)) {
                fp++;
            }
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;

        log.info("📊 === Shutdown Stats ===");
        log.info("📊 Producer: RPS={}, requests={}, duration(ms)={}",
                producerStats.rps(), producerStats.count(), producerStats.durationNs() / (long) 1e6);
        log.info("📊 Ground truth batches: {}, Alerts received: {}, Blocked cards: {}",
                truthMap.size(), alertMap.size(), blockedCards.size());
        log.info("📊 Confusion matrix: TP={}, FP={}, FN={}", tp, fp, fn);
        log.info("📊 Precision: {}, Recall: {}", String.format("%.4f", precision), String.format("%.4f", recall));

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            log.info("📊 Detection latency (ms): p50={}, p95={}, p99={}",
                    percentile(latencies, 50),
                    percentile(latencies, 95),
                    percentile(latencies, 99));
        }
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
