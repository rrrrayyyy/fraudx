# Fraud Detection Logic Design

## Overview

Payment service generates synthetic payment events with controlled fraud patterns.
Fraud detection service ingests events, persists to ScyllaDB, detects fraud via query, and publishes alerts.
Payment service subscribes to alerts, blocks cards, and outputs stats at shutdown.

## Data Generation (Payment Service)

### Card-centric generation

```
Input: POST /payment-events?n=N

Total cards     = N / 1000 (remainder card gets fewer events, never fraudulent)
Per card        = 1000 events = 1000/threshold mini-batches
Per mini-batch  = threshold events (e.g., 5)
```

### Fraud probability

- p = 0.05% per mini-batch
- Per card (threshold=5): P(at least 1 fraud) = 1 - (1-0.0005)^200 = 9.5%
- Expected fraud cards for N=10M (10K cards): ~950

| N | Cards | Expected fraud cards | Expected fraud batches |
|---|-------|---------------------|----------------------|
| 100K | 100 | ~9.5 | ~10 |
| 1M | 1,000 | ~95 | ~100 |
| 10M | 10,000 | ~950 | ~1,000 |
| 100M | 100,000 | ~9,500 | ~10,000 |

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
LIMIT ?  -- threshold
```

Fraud condition: result count == threshold AND first.processed_at - last.processed_at <= duration.

If fraud detected, extract batch_id from result (all rows share the same batch_id within
a fraud mini-batch) and publish alert.

### Why this query works with skew

- Mini-batches per card are published sequentially (processed_at increases across batches)
- After inserting batch N, `LIMIT threshold` returns batch N's events (most recent by processed_at)
- Within a mini-batch, events may arrive out of order, but ScyllaDB sorts by processed_at DESC
- No sliding window or +M lookahead needed

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

Insert and detection queries use the driver default (`LOCAL_ONE`). With multiple consumer
threads writing to different replicas, a detection query may read from a replica that has
not yet received all events from other threads. This can cause false negatives (missed
detections) when the threshold-completing event is written to replica A but the query
reads from replica B.

To improve detection accuracy, set consistency level to `LOCAL_QUORUM` on both insert
and detection queries. This ensures writes are acknowledged by a majority of replicas
and reads see the latest majority-acknowledged data. The tradeoff is higher per-query
latency and reduced availability under node failures.

### Alert deduplication

Not required. Same card's mini-batches are published sequentially by payment service.
The consumer that inserts the threshold-completing event is the only one that detects fraud.
Even in the rare race condition where two consumers query simultaneously, payment-side
handling is idempotent (ConcurrentHashMap.put).

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
```

Injected into:
- `PaymentEventsProduceUseCase`: writes GroundTruth, reads BlockedCards
- `FraudAlertConsumer`: writes AlertStore + BlockedCards
- `ShutdownStatsReporter`: reads GroundTruth + AlertStore

## Shutdown Stats

### Trigger

`@PreDestroy` on ShutdownStatsReporter. No automatic drain period. The operator
manually waits for the pipeline to finish processing (observing fraud-detection logs)
before stopping payment service. This avoids the complexity of estimating drain
duration which varies with N.

### Metrics

| Metric | Source | Status |
|--------|--------|--------|
| Producer RPS / count / duration | PaymentEventsProduceUseCase | Implemented |
| Consumer RPS / count / duration | fraud-detection KafkaClient | Implemented |
| Confusion matrix (TP/FP/FN/TN) | GroundTruth vs AlertStore (batch_id match) | TODO |
| Precision / Recall | Derived from confusion matrix | TODO |
| Detection latency p50/p95/p99 | Per matched batch_id: alert_time - completion_time | TODO |

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
common              fraud rules (TransactionFrequencyRule, FraudRulesProperties, rules.yaml).
                    depends on: spring-boot-starter
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
