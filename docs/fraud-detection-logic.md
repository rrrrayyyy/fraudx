# Fraud Detection Logic Design

## Overview

Payment service generates synthetic payment events with **simulated per-card timelines**
and publishes them to Kafka. Each card maintains its own chronological timeline with
realistic inter-event gaps (jitter). Fraud is injected as variable-size bursts of rapid
transactions with configurable probability. Fraud detection service ingests events,
persists to ScyllaDB, detects fraud via per-card range queries with sliding window,
and publishes alerts. Ground truth is computed post-hoc at shutdown via full ScyllaDB scan.

## Data Generation (Payment Service)

### Per-card timeline model

```
Input: POST /payment-events?n=N

cardPool       = pre-allocate C cards with (cardId, userId, paymentMethod)
lastTimestamps = Map<cardId, Instant>, all cards initialized to now()

for i = 0; i < N;:
    card = cardPool[random.nextInt(C)]
    if blocked(card.cardId): continue

    remaining = N - i
    if remaining >= 2 and random() < fraudProbability:
        burstSize = random[2, threshold * burstMultiplier]  // inclusive [2, 15]
        burstSize = min(burstSize, remaining)
        burstStart = lastTimestamps.get(card.cardId) + random(jitterMin, jitterMax)
        generate burstSize timestamps, each = burstStart + random(0, duration)
        sort timestamps ascending
        publish all burstSize events with card info and sorted timestamps
        lastTimestamps.put(card.cardId, last timestamp in sorted array)
        i += burstSize
    else:
        // Normal event
        newTimestamp = lastTimestamps.get(card.cardId) + random(jitterMin, jitterMax)
        lastTimestamps.put(card.cardId, newTimestamp)
        publish event with card info and newTimestamp
        i += 1
```

A fraud burst consumes `burstSize` from the loop counter. The `remaining >= 2`
guard ensures room for the minimum burst. `burstSize` is capped at `remaining`
to prevent exceeding N.

Each event is published via `kafkaTemplate.send()` (async, non-blocking). The Kafka key
is a unique transaction_id, so events for the same card land on different partitions.

### Simulated timestamps

Events use simulated time, not wall-clock time. The producer advances simulated time via
the per-card lastTimestamps map, generating events at Kafka speed (~300K+ RPS) while
timestamps span realistic timelines (~25 days of simulated time for 1M events with C=10K).

- `processed_at`: simulated timestamp from the per-card timeline (used for fraud detection)
- `created_at`: `Instant.now()` wall-clock time (used for end-to-end latency measurement)

### Fraud burst model

With probability Pf per event opportunity, a card generates a burst of rapid transactions
instead of a single normal event. This models real-world fraud where transaction counts
vary per incident:

- **Burst size**: `random[2, threshold * burst-multiplier]` = uniform [2, 15] with default
  threshold=5, burst-multiplier=3
- **Burst timing**: all events within `duration` (default 60s) of each other
- **Guard**: remaining >= 2 (minimum burst size), burstSize capped at remaining

Bursts with burstSize < threshold produce 0 detection clusters (fewer events than the
sliding window requires). Bursts with burstSize >= threshold produce `burstSize - threshold + 1`
clusters due to the exhaustive no-skip sliding window.

After a fraud burst, the card resumes its normal timeline with a fresh jitter gap.

### Blocking in benchmarks

The producer generates all N events at Kafka speed (~3 seconds for 1M events). The
detection-to-alert pipeline takes longer than this. In practice, all events are published
before any alert arrives, so blocking has no effect on benchmark results. The blocking
logic is retained for production realism but does not affect metrics.

## Fraud Detection (Fraud Detection Service)

### Event ingestion

1. Kafka consumer receives batch of payment events (mixed cards, simulated timestamps)
2. `PaymentEventsConsumeUseCase.execute()` calls `insertAll()` to ScyllaDB with retry

### Detection algorithm

After `insertAll()`, events are grouped by card_id with min/max `processed_at` tracked
per card. One range query per distinct card retrieves all events in the relevant time
window, then a client-side sliding window checks for fraud clusters.

#### Step 1: Per-card min/max

```java
Map<String, Instant[]> ranges = new HashMap<>();  // cardId -> [min, max]
for (PaymentEvent event : batch) {
    ranges.merge(event.cardId(),
        new Instant[]{event.processedAt(), event.processedAt()},
        (a, b) -> new Instant[]{
            a[0].isBefore(b[0]) ? a[0] : b[0],
            a[1].isAfter(b[1]) ? a[1] : b[1]
        });
}
```

#### Step 2: Range query per card

```sql
SELECT processed_at FROM payment_events_by_card
WHERE card_id = ?
  AND processed_at >= ?    -- min(processedAt) - duration
  AND processed_at <= ?    -- max(processedAt)
```

Bind: `(cardId, min - duration, max)`

The query range extends `duration` before the earliest batch event to capture clusters
that started before this batch but include events from it. Per-card event density within
any `duration`-wide window is low (normal events spaced jitterMin to jitterMax apart),
so result sets are small. All queries execute in parallel via `executeAsync` — ScyllaDB
`IN` across partitions is an anti-pattern (coordinator fan-out), while parallel
`executeAsync` lets each query go directly to the owning node.

**Visibility bound**: each thread's query range is bounded by the batch events'
processedAt. A thread processing an event with processedAt=T can only see events
with processedAt <= T.

#### Step 3: Sliding window (exhaustive, no skip)

```
for each query result (sorted DESC by clustering key):
    i = 0
    while i <= rows.size() - threshold:
        if rows[i] - rows[i + threshold - 1] <= duration:
            emit detection(cardId, clusterTimestamp=rows[i])
        i++
```

On detection: `cluster_timestamp = rows[i]` (latest event in the cluster),
`trigger_created_at = event.created_at` (from the batch event that initiated the query).

### Cross-batch re-detection and dedup

Burst events are distributed across multiple Kafka partitions (key = transaction_id),
so they arrive in different consumer poll batches. Each batch independently queries
ScyllaDB after inserting its events. If all burst events are already persisted, multiple
batches can detect the same cluster and emit duplicate alerts.

Dedup prevents duplicate alert publishing:

- **Dedup key**: `(cardId, clusterTimestamp)` — uniquely identifies a detection result
- **Data structure**: `ConcurrentHashMap<String, Set<Instant>>` shared across all consumer
  threads (cardId → set of published clusterTimestamps)
- **Check**: before publishing an alert, attempt to add clusterTimestamp to the card's set.
  If already present, the alert is a duplicate and is not published

```
for each detection:
    timestamps = publishedAlerts.computeIfAbsent(cardId, k -> ConcurrentHashMap.newKeySet())
    if timestamps.add(clusterTimestamp):
        alertPublisher.publish(detection)
```

Detection and ground truth both use the same exhaustive sliding window (no skip),
so their cluster counts are consistent for the same data.

### Detection query skip (benchmarked, reverted)

Most cards in a batch have no recent activity within `duration` and will never trigger
detection. Reading the latest `processedAt` per card from ScyllaDB before insertion
can identify these cards and eliminate unnecessary range queries.

**Algorithm**:

```
1. Before insertAll(): for each distinct card in the batch, read latest processedAt
   from ScyllaDB (SELECT processed_at ... WHERE card_id = ? LIMIT 1, parallel executeAsync)
2. After insertAll(): for each card, compare batchMin(processedAt) with the
   pre-insertion latestProcessedAt
3. Skip the detection range query if latestProcessedAt < batchMin - duration
   (no events from this card existed in the detection window before this batch)
4. Run detectFraud() only for non-skipped cards
```

Benchmarked on single-machine Docker (M4 Pro, N=10M): consumer RPS dropped from ~80K
to ~46K. Cause: the ~6,321 LIMIT 1 reads per batch add a sequential phase before
insertAll, but range queries returning 0-1 rows (the common case) are already cheap.
Total ScyllaDB operations are unchanged (~16K per batch), and the extra sequential step
increases latency. Same I/O contention pattern as async detection.

This optimization is viable only when ScyllaDB nodes run on separate machines with
independent I/O, where point reads are significantly cheaper than range queries.

### Alert publishing

On fraud detection, publish to `fraud-alerts` topic:

```protobuf
message FraudAlertKey {
    string card_id = 1;
}
message FraudAlertValue {
    reserved 1, 2;
    google.protobuf.Timestamp detected_at = 3;
    google.protobuf.Timestamp trigger_created_at = 5;
}
```

- `detected_at`: wall-clock time when fraud-detection-service detected the cluster
- `trigger_created_at`: `created_at` of the triggering event (wall-clock time when it
  was published), used for end-to-end latency calculation

### Consistency level

Insert and detection queries use `LOCAL_QUORUM` (RF=3, quorum=2).

`LOCAL_QUORUM` on both insert and detect guarantees quorum intersection: a write
acknowledged by 2/3 replicas is always visible to a read from 2/3 replicas. This
eliminates cross-thread visibility issues for completed writes. The remaining race
condition (in-flight writes from concurrent threads) is narrow — the last thread to
complete `insertAll()` sees all other threads' completed writes.

## Alert Handling (Payment Service)

### Subscribe (fraud-alerts)

- Single-event consumer (not batch) for fraud-alerts topic
- On receive: add card_id to `BlockedCards`, increment per-card alert count in `AlertStore`,
  record detection latency (arrived_at - detected_at)

### Live blocking

- Before each event publish, check `BlockedCards.contains(card_id)`
- If blocked, skip (do not publish or update state for this card)

Note: in benchmarks, blocking has no practical effect (see "Blocking in benchmarks" above).

## Shared State (Payment Service)

```
AlertStore:       ConcurrentHashMap<String, AtomicInteger>  // card_id -> alert count
                  + synchronized List<Long>                 // detection latencies (ms)
BlockedCards:     ConcurrentHashMap.newKeySet<String>()     // card_id set
ProducerStats:    AtomicInteger count + AtomicLong durationNs
```

Ground truth is computed post-hoc at shutdown by scanning ScyllaDB directly.

Injected into:
- `PaymentEventsProduceUseCase`: reads BlockedCards, writes ProducerStats
- `FraudAlertConsumer`: writes AlertStore + BlockedCards
- `ShutdownStatsReporter`: reads AlertStore + ProducerStats, queries ScyllaDB

## Shutdown Stats

### Trigger

`@PreDestroy` on ShutdownStatsReporter. The operator manually waits for the pipeline to
finish processing (observing fraud-detection logs or Kafka UI lag=0) before stopping
payment service.

### Post-hoc ground truth scan

At shutdown, ShutdownStatsReporter connects to ScyllaDB (lazy CqlSession, not created at
startup) and computes ground truth:

```
1. SELECT DISTINCT card_id FROM payment_events_by_card
2. For each card_id:
     SELECT processed_at FROM payment_events_by_card
     WHERE card_id = ? ORDER BY processed_at DESC
3. Run sliding window on full event history (exhaustive, no skip):
     clusterCount = 0
     for i in 0..rows.size()-threshold:
         if rows[i] - rows[i+threshold-1] <= duration:
             clusterCount++
4. groundTruth[card_id] = clusterCount
```

This scan sees all events (complete data), providing authoritative ground truth.
It captures both intentional fraud bursts and any accidental normal-event clusters
that happen to satisfy the detection rule.

### Confusion matrix calculation

Per card, compare ground truth cluster count with alert count:

```
For each card_id:
    truth  = groundTruth.getOrDefault(cardId, 0)
    alerts = alertStore.getCount(cardId)
    TP += min(truth, alerts)
    FP += max(0, alerts - truth)
    FN += max(0, truth - alerts)
```

### Detection latency

Measured per alert as `arrived_at - trigger_created_at`:
- `trigger_created_at`: wall-clock time when the fraud-triggering event was published by payment-service
- `arrived_at`: `Instant.now()` when payment-service receives the alert

This measures end-to-end latency from event publication through detection and alert delivery.

### Metrics

| Metric | Source | Affected by |
|--------|--------|-------------|
| Producer RPS / event count / duration | ProducerStats | — |
| Consumer RPS / event count / duration | fraud-detection KafkaClient | System config (partitions, batch size, ScyllaDB I/O) |
| Confusion matrix (TP/FP/FN) | Post-hoc scan vs AlertStore (card-level) | — |
| Precision | Derived from confusion matrix | Algorithm correctness |
| Recall | Derived from confusion matrix | Cross-partition write timing |
| Detection latency p50/p95/p99 | Per alert: arrived_at - trigger_created_at | System load, consumer lag |

## Benchmark Analysis

### Precision

Normal events are spaced uniformly between jitterMin and jitterMax apart per card. The probability of
normal events accidentally forming a cluster of `threshold` within `duration` is near-zero:

```
P(4 consecutive jitters <= 15s) = ((15 - 5) / (43200 - 5))^4 ~ 2.9e-15
```

With dedup, duplicate alerts from cross-batch re-detection are suppressed. Each unique
(cardId, clusterTimestamp) is published at most once. Every deduplicated alert therefore
corresponds to a genuine fraud burst.

Expected precision: **~1.0**.

### Recall

Detection requires at least `threshold` burst events to be visible in ScyllaDB when a
batch's query runs. With fixed burst size = threshold, all events must be persisted —
a tight race window that yields recall ~0.60.

Variable burst size models real-world fraud where transaction counts vary per incident.
As a consequence, bursts with burstSize > threshold contain more events than needed, so
detection succeeds even when some events are not yet visible. For example, a burst of 10
events (threshold=5) is detectable as long as any 5 within `duration` are persisted.

Bursts with burstSize < threshold produce 0 clusters by design — these are not recall
misses but intentional sub-threshold fraud attempts.

Cross-batch re-detection further helps: if batch A misses a cluster, batch B processing
a later event may detect it after more events are visible. Dedup ensures only one alert
per unique detection.

Expected recall: **~0.94-0.95**.

## Infrastructure

### Module structure

```
proto                    protobuf definitions only. depends on: protobuf-java
kafka                    KafkaProtobufSerializer/Deserializer (shared). depends on: proto, kafka-clients
common                   fraud rules (TransactionFrequencyRule, FraudRulesProperties, rules.yaml).
                         depends on: spring-boot-starter
payment-service          depends on: proto, kafka, common, scylla-driver (for post-hoc scan)
fraud-detection-service  depends on: proto, kafka, common, scylla-driver
```

### fraud-alerts topic

- Partitions: 3 (consistent with broker count)
- Replication factor: 3
- Auto-create via KafkaTopicConfig

### Proto messages

`event.proto`:
```protobuf
message PaymentEventKey { string transaction_id = 1; }
message PaymentEventValue {
    reserved 2, 8, 10, 11, 12;
    Account account = 1;
    int64 amount = 3;
    Currency currency = 4;
    PaymentMethod payment_method = 5;
    google.protobuf.Timestamp processed_at = 6;
    google.protobuf.Timestamp created_at = 7;
}
```

`alert.proto`:
```protobuf
message FraudAlertKey { string card_id = 1; }
message FraudAlertValue {
    reserved 1, 2;
    google.protobuf.Timestamp detected_at = 3;
    google.protobuf.Timestamp trigger_created_at = 5;
}
```

### ScyllaDB schema

```sql
CREATE TABLE payment_events_by_card (
    card_id TEXT,
    processed_at TIMESTAMP,
    transaction_id TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY (card_id, processed_at, transaction_id)
) WITH CLUSTERING ORDER BY (processed_at DESC)
  AND compaction = {'class': 'TimeWindowCompactionStrategy'}
```

### Configuration

| Parameter | Location | Default | Description |
|-----------|----------|---------|-------------|
| `threshold` | `rules.yaml` | 5 | Events per sliding window to trigger detection |
| `duration` | `rules.yaml` | 1m | Max time span for threshold events |
| `cards` | `BENCHMARK_CARDS` | 10000 | Number of distinct cards |
| `fraud-probability` | `BENCHMARK_FRAUD_PROBABILITY` | 0.00004 | Probability of fraud burst per event opportunity |
| `burst-multiplier` | `BENCHMARK_BURST_MULTIPLIER` | 3 | Upper bound factor for burst size: random[2, threshold * burst-multiplier] |
| `jitter-min` | `BENCHMARK_JITTER_MIN` | 5s | Normal event interval lower bound |
| `jitter-max` | `BENCHMARK_JITTER_MAX` | 12h | Normal event interval upper bound |

### Pf derivation (alert count target)

```
alerts ~ N * Pf * clusters_per_burst * recall

clusters per burst (no-skip, burst = random[2, 15], threshold = 5):
  burst 2: 0, burst 3: 0, burst 4: 0, burst 5: 1, burst 6: 2, ..., burst 15: 11
  average clusters = (0+0+0+1+2+3+4+5+6+7+8+9+10+11) / 14 = 66/14 ~ 4.71

For N = 10M, recall ~ 0.95:
  Pf = 0.00004 -> fraud bursts ~ 400, expected alerts ~ 400 * 4.71 * 0.95 ~ 1,790

Real-world fraud probability range: 0.00003-0.00015
Alert count scales linearly with N.
```

## Changes from 6e442ba

| Aspect | 6e442ba | Current (per-card timeline) |
|--------|-------------|-----------------------------|
| Data generation | Deterministic mini-batches per card, `FRAUD_PROBABILITY`, `SIMULATION_DAYS=7` | Random card selection, simulated per-card timestamps, fraud burst injection |
| Timestamp model | Random within 7-day range | Simulated per-card timeline, jitter between jitterMin and jitterMax |
| Fraud injection | FRAUD_PROBABILITY per batch | fraudProbability per event opportunity, burst = random[2, threshold * burst-multiplier] events |
| Ground truth | `batch_id` match at generation time | Post-hoc ScyllaDB scan (sliding window over full event history) |
| Dedup | Yes (7-day temporal overlap) | Yes (cross-batch re-detection, key = cardId + clusterTimestamp) |
| Precision | ~1.0 (deterministic split) | ~1.0 (rule matches data model) |
| Recall | ~1.0 (lookback covers all) | ~0.94-0.95 (cross-partition write timing) |

## Async detection (tested in previous design, not implemented)

Decouple detection from the insert critical path: after `insertAll()`, submit
detection to a fixed thread pool so the consumer thread polls the next batch
immediately.

```
Current:  [insert] -> [detect + alert] -> poll next batch
Proposed: [insert] -> poll next batch
                └──> [detect + alert] (detection thread pool)
```

Benchmarked on single-machine Docker (M3 Pro, N=10M): consumer RPS dropped 10%
(82K -> 74K). Cause: ScyllaDB read/write I/O contention. All 3 nodes share the
same SSD/memory, so concurrent insert + detect queries compete for I/O bandwidth.
Sequential execution avoids this by serializing read and write phases.

This optimization is viable only when ScyllaDB nodes run on separate machines with
independent I/O. Not applicable to the current single-machine benchmark setup.
