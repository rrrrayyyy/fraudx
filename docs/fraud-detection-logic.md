# Fraud Detection Logic Design

## Overview

Payment service generates synthetic payment events with controlled fraud patterns.
Fraud detection service ingests events, persists to ScyllaDB, detects fraud via query, and publishes alerts.
Payment service subscribes to alerts, blocks cards, and outputs stats at shutdown.

## Data Generation (Payment Service)

### Card-centric generation

```
Input: POST /payment-events?n=N

Total cards     = 2N / (threshold + maxEventsPerCard)
maxEventsPerCard = 7 days / duration   (duration=1min → 10,080)
Per card        = Uniform[threshold, maxEventsPerCard], last card absorbs remainder (sum = N)
Mini-batches    = ceil(eventsForCard / threshold) (varies per card)
Per mini-batch  = threshold events (e.g., 5)
```

### Fraud probability

- p = 0.05% per mini-batch
- Per card (threshold=5, avg ~1,008 mini-batches): P(at least 1 fraud) = 1 - (1-0.0005)^1008 ≈ 39.5%
- Expected fraud cards for N=10M (1,983 cards): ~783

| N | Cards | Expected fraud cards | Expected fraud batches |
|---|-------|---------------------|----------------------|
| 100K | 20 | ~8 | ~10 |
| 1M | 198 | ~78 | ~100 |
| 10M | 1,983 | ~783 | ~1,000 |
| 100M | 19,834 | ~7,834 | ~10,000 |

A card may have multiple fraudulent mini-batches. Each is independently recorded
and expected to produce a separate alert.

### Timestamp generation

Each mini-batch reserves a time range of `duration * threshold` (e.g., 1min * 5 = 5min).

**Normal mini-batch:**
Events spaced by `duration`. Example (threshold=5, duration=1min):
```
t=base, base+1min, base+2min, base+3min, base+4min
```
Any 5 events span 4min > 1min duration. Never triggers the rule.

**Fraudulent mini-batch:**
Events clustered within `duration` using non-negative jitter. Example:
```
t=base+j1, base+j2, ..., base+j5  (0 <= jitter < duration)
```
All 5 events within 1min. Triggers the rule.
Jitter must be >= 0 to avoid bleeding into the previous mini-batch's time range.

Next mini-batch base = previous base + duration * threshold.

### Publishing model

```
for each card (sequential):
    for each mini-batch (sequential):
        decide fraud (0.05%) or normal
        generate threshold events with timestamps
        publish threshold events via kafkaTemplate.send() (async, non-blocking)
        if fraud: record to GroundTruth
```

Skew arises from partition distribution: each event's Kafka key is a unique
transaction_id, so events within a mini-batch land on different partitions.
Different partitions are consumed by independent consumer threads, making
the processing order non-deterministic relative to processed_at order.

### batch_id (test instrumentation)

Each mini-batch is assigned a unique batch_id (UUID). This field exists solely for
matching ground truth to alerts in stats computation. It is NOT part of fraud detection logic.

- Added to `PaymentEventValue` proto (field 8, confirmed never used in git history)
- Stored in ScyllaDB table
- Propagated to `FraudAlertValue` proto
- Commented as test instrumentation in proto and code

## Fraud Detection (Fraud Detection Service)

### Event ingestion

Existing flow unchanged:
1. Kafka consumer receives batch of payment events (mixed cards, mixed mini-batches)
2. `PaymentEventsConsumeUseCase.execute()` calls `insertAll()` to ScyllaDB with retry

### Detection query

After `insertAll()`, extract unique card_ids from the batch. For each card_id, execute
detection query in parallel via `executeAsync`:

```sql
SELECT processed_at, batch_id FROM payment_events_by_card
WHERE card_id = ?
ORDER BY processed_at DESC
LIMIT ?  -- lookback (configurable, default 1000)
```

### Sliding window detection

`LIMIT threshold` from the global latest is insufficient. Kafka key is transaction_id
(not card_id), so events from the same mini-batch land on different partitions. Multiple
consumer threads insert events concurrently, and normal events from later mini-batches
can be inserted before all fraud events are visible. `LIMIT threshold` returns only the
most recent events, which are dominated by later normal mini-batches.

Instead, `LIMIT lookback` retrieves a larger window of events and applies a sliding
window of size `threshold`:

```
for i in 0..rows.size()-threshold:
    span = rows[i].processed_at - rows[i+threshold-1].processed_at
    if span <= duration:
        fraud detected (batch_id from rows[i])
        skip ahead by threshold to avoid re-detecting same batch
```

`lookback` is a tuning parameter controlling how many recent events per card are
examined. Events per card range from threshold to ~10,080 (avg ~5,043 for
duration=1min). Higher lookback improves recall at the cost of increased ScyllaDB
read I/O per detection query. Expected recall ≈ (L + L·ln(10080/L)) / 10075
where L = lookback (derived from Uniform[threshold, 10080] distribution).

False positives are structurally impossible: normal mini-batches have events spaced by
`duration`, so any `threshold` consecutive normal events span ≥ (threshold-1)×duration
\>> duration. This is a consequence of deterministic test data generation (required
for verifiable ground truth without post-hoc scan of all N events). As a result,
precision is always 100% and threshold does not affect benchmark results. The only
variable under test is `lookback`.

### Why parallel executeAsync, not IN clause

ScyllaDB `IN` across partitions causes coordinator fan-out and is an anti-pattern.
Parallel `executeAsync` per card_id is faster: each query goes directly to the owning node.
Same pattern as existing `insertAll()`.

### Alert publishing

On fraud detection, publish to `fraud-alerts` topic:

```protobuf
message FraudAlertKey {
    string card_id = 1;
}
message FraudAlertValue {
    reserved 1;
    string batch_id = 2;  // test instrumentation: for ground truth matching only
    google.protobuf.Timestamp detected_at = 3;
}
```

`detected_at` is a record timestamp for when the fraud was detected. It is NOT used for
detection latency calculation. Detection latency = alert arrival time (`Instant.now()` at
payment-service consumer) - mini-batch completion time (from GroundTruth). This measures
end-to-end latency including Kafka transit, not just fraud-detection-service processing time.

### Consistency level

Insert and detection queries use `LOCAL_QUORUM` (RF=3, quorum=2). With multiple consumer
threads writing to different replicas concurrently, `LOCAL_ONE` would cause false negatives
when a detection query reads from a replica that has not yet received another thread's writes.

`LOCAL_QUORUM` on both insert and detect guarantees quorum intersection: a write
acknowledged by 2/3 replicas is always visible to a read from 2/3 replicas. This
eliminates cross-thread visibility issues for completed writes. The remaining race
condition (in-flight writes from concurrent threads) is narrow — the last thread to
complete `insertAll()` sees all other threads' completed writes.

Measured overhead: Consumer RPS with `LOCAL_QUORUM` is within 2% of `LOCAL_ONE`.

### Alert deduplication

Required in fraud-detection-service. With sliding window, the same fraud batch can be
detected by multiple consumer threads (each querying the same card_id independently).
A `ConcurrentHashMap.newKeySet()` of detected batch_ids in `PaymentEventsConsumeUseCase`
prevents duplicate alert publishing. Payment-side handling is also idempotent
(`ConcurrentHashMap.put`).

## Alert Handling (Payment Service)

### Subscribe (fraud-alerts)

- Single-event consumer (not batch) for fraud-alerts topic
- On receive: add card_id to `BlockedCards`, record alert in `AlertStore`

### Live blocking

- Before each mini-batch publish, check `BlockedCards.contains(card_id)`
- If blocked, skip remaining mini-batches for that card
- No mid-batch blocking (mini-batch events are published as a unit)

In production, customers continue transacting even when compromised, so blocking
prevents further damage. In this test scenario, blocking may only take effect after
a round-trip delay (~100ms+), but this accurately reflects real-world latency between
fraud detection and enforcement.

## Shared State (Payment Service)

All shared state is held in mutable Spring singleton beans with thread-safe collections.
Spring singleton scope means single instance per ApplicationContext (not immutable).
No ScyllaDB or external storage needed.

```
FraudGroundTruth: ConcurrentHashMap<String, Instant>        // batch_id -> mini-batch completion time
AlertStore:       ConcurrentHashMap<String, Instant>        // batch_id -> alert arrival time (Instant.now())
BlockedCards:     ConcurrentHashMap.newKeySet<String>()     // card_id set
ProducerStats:    AtomicInteger count + AtomicLong durationNs  // producer performance
```

Injected into:
- `PaymentEventsProduceUseCase`: writes GroundTruth, reads BlockedCards, writes ProducerStats
- `FraudAlertConsumer`: writes AlertStore + BlockedCards
- `ShutdownStatsReporter`: reads GroundTruth + AlertStore + ProducerStats

## Shutdown Stats

### Trigger

`@PreDestroy` on ShutdownStatsReporter. No automatic drain period. The operator
manually waits for the pipeline to finish processing (observing fraud-detection logs)
before stopping payment service. This avoids the complexity of estimating drain
duration which varies with N.

### Metrics

| Metric | Source | Status |
|--------|--------|--------|
| Producer RPS / count / duration | ProducerStats (via ShutdownStatsReporter) | Implemented |
| Consumer RPS / count / duration | fraud-detection KafkaClient | Implemented |
| Confusion matrix (TP/FP/FN/TN) | GroundTruth vs AlertStore (batch_id match) | Implemented |
| Precision / Recall | Derived from confusion matrix | Implemented |
| Detection latency p50/p95/p99 | Per matched batch_id: alert_time - completion_time | Implemented |

### Confusion matrix calculation

Per batch_id:
- **TP (True Positive)**: batch_id in GroundTruth AND in AlertStore (correctly detected)
- **FP (False Positive)**: batch_id NOT in GroundTruth AND in AlertStore (false alarm)
- **FN (False Negative)**: batch_id in GroundTruth AND NOT in AlertStore (missed)
- **TN (True Negative)**: total mini-batches - TP - FN - FP (correctly ignored)

Detection latency calculated only for TP entries (matched batch_ids).

## Infrastructure

### Module structure

```
proto               protobuf definitions only. depends on: protobuf-java
kafka               KafkaProtobufSerializer/Deserializer (shared). depends on: proto, kafka-clients
common              fraud rules (TransactionFrequencyRule{enabled,threshold,duration,lookback},
                    FraudRulesProperties, rules.yaml). depends on: spring-boot-starter
payment-service     depends on: proto, kafka, common
fraud-detection-service
                    depends on: proto, kafka, common
```

KafkaProtobufSerializer (from payment-service) and KafkaProtobufDeserializer
(from fraud-detection-service) are moved to a new kafka module. This keeps proto
as a pure protobuf module with no Kafka dependency.

### fraud-alerts topic

- Partitions: 3 (consistent with broker count; even at N=100M only ~10K alerts, 1 would suffice functionally)
- Replication factor: 3
- Auto-create via KafkaTopicConfig (same pattern as payment-events)

### Proto changes (proto module)

- `PaymentEventValue`: add `string batch_id = 8` (test instrumentation, field 8 confirmed unused in git history)
- New messages: `FraudAlertKey`, `FraudAlertValue` in new `alert.proto`

### New kafka module

- `KafkaProtobufSerializer` (moved from payment-service)
- `KafkaProtobufDeserializer` (moved from fraud-detection-service)
- depends on: proto, kafka-clients

### ScyllaDB changes

- Add `batch_id text` column to `payment_events_by_card`
- Add detection query as PreparedStatement

### Payment service additions

- Kafka consumer config for fraud-alerts (using shared deserializer from kafka module)
- KafkaTopicConfig for fraud-alerts topic
- FraudGroundTruth, AlertStore, BlockedCards beans
- FraudAlertConsumer
- ShutdownStatsReporter
- ProduceUseCase rewrite (card-centric generation)

### Fraud detection service additions

- Kafka producer config for fraud-alerts (using shared serializer from kafka module)
- Detection logic in PaymentEventsConsumeUseCase (after insertAll)
- FraudRulesProperties wiring (EnableConfigurationProperties already done)

## Async detection (not implemented — production-only optimization)

Decouple detection from the insert critical path: after `insertAll()`, submit
detection to a fixed thread pool so the consumer thread polls the next batch
immediately.

```
Current:  [insert] → [detect + alert] → poll next batch
Proposed: [insert] → poll next batch
                └──→ [detect + alert] (detection thread pool)
```

Benchmarked on single-machine Docker (M3 Pro, N=10M): consumer RPS dropped 10%
(82K → 74K). Cause: ScyllaDB read/write I/O contention. All 3 nodes share the
same SSD/memory, so concurrent insert + detect queries compete for I/O bandwidth.
Sequential execution avoids this by serializing read and write phases.

This optimization is viable only when ScyllaDB nodes run on separate machines with
independent I/O. Not applicable to the current single-machine benchmark setup.

## Procedures

```zsh
make up
make logs-fraud

make post-event n=10000000

make fraud-rps
make payment-stats

make cql
```

Order matters: `fraud-rps` stops fraud-detection-service (must finish processing
and publishing all alerts first), then `payment-stats` stops payment-service
(triggers @PreDestroy → ShutdownStatsReporter with all alerts already received).

### New Makefile target

```makefile
payment-stats:
	$(DC_BASE) stop payment-service
	docker logs $(PAYMENT_LOG) | tail -50
```
