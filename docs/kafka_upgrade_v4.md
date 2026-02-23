# Kafka 4.x Upgrade and Performance Optimization Guide

This document outlines the necessary steps and configuration changes to fully upgrade the `fraudx` project to Apache Kafka 4.x, optimize for high throughput, and ensure data integrity.

## 1. Upgrade Prerequisites

### Dependencies & Compatibility
**Facts Verified:**
- **Kafka 4.0 Requirement:** Kafka 4.0 completely removes ZooKeeper mode. It runs exclusively in KRaft mode.
- **Java Version:** Kafka 4.0 Brokers require Java 17+. Kafka Clients require Java 11+. This project uses Java 25, so it is fully compatible.
- **Client Compatibility:** The project uses `org.apache.kafka:kafka-clients:4.1.0`, which is the latest.

**Current Status:**
- `fraud-detection-service/build.gradle`: Uses `kafka-clients:4.1.0` ✅
- `payment-service/build.gradle`: Uses `kafka-clients:4.1.0` ✅
- `compose.yaml`: Uses `apache/kafka:4.1.0` and is configured for KRaft (`KAFKA_PROCESS_ROLES: controller,broker`). ✅

### Action Required
No dependency changes are needed. The project is already using Kafka 4.x binaries.

---

## 2. Producer Optimization (Payment Service)

### Configuration Changes
To improve throughput, we will move from default/low-latency settings to high-throughput batching settings.

**File:** `payment-service/src/main/resources/application.yaml`

| Setting | Current | Recommended | Reason |
| :--- | :--- | :--- | :--- |
| `compression-type` | `none` | **`zstd`** | `zstd` offers the best balance of compression ratio and CPU usage in Kafka 4.x. |
| `batch-size` | `16384` | **`65536`** (64KB) | Larger batches reduce network requests and improve compression. |
| `linger-ms` | `5` | **`20`** | Waiting 20ms allows batches to fill up, significantly boosting throughput with minimal latency impact. |
| `acks` | `all` | `all` | Maintain `all` for data safety. |
| `buffer-memory` | `33554432` | *(Remove)* | Default (32MB) is sufficient unless memory pressure is observed. |

### Code Optimization: Remove Redundant Config Class
**File:** `payment-service/src/main/java/com/example/payment_service/adapter/KafkaConfig.java`

**Recommendation:** **Delete this class.**
The current `KafkaConfig` class manually maps properties to `ProducerFactory`. This is redundant because Spring Boot automatically configures `KafkaTemplate` based on `application.yaml` properties (`spring.kafka.*`). Removing this class reduces boilerplate and prevents configuration drift.

**New `application.yaml`:**

```yaml
spring:
  application:
    name: payment-service
  kafka:
    bootstrap-servers: broker-1:19092,broker-2:19092,broker-3:19092
    producer:
      # High throughput settings
      compression-type: zstd
      batch-size: 65536
      linger-ms: 20
      acks: all
      key-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
      value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
```

---

## 3. Consumer Optimization (Fraud Detection Service)

### Consumer (Fraud Detection Service)

**File:** `fraud-detection-service/src/main/resources/application.yaml`

| Kafka Property | Current | Recommended | Reason |
| :--- | :--- | :--- | :--- |
| `fetch.min.bytes` | `1` | **`1024`** | Reduces "chatty" requests by waiting for at least 1KB of data. |
| `max.partition.fetch.bytes`| `1MB` | *(Remove)* | Default (1MB) is fine. |
| `enable.auto.commit` | `true` | **`false`** | **CRITICAL:** Must be `false` to prevent data loss. We need manual control to commit *after* async processing completes. |
| `max.poll.records` | `500` | *(Remove)* | Default is 500. |

**Note:** Some of these are not standard Spring Boot properties and must be set under `spring.kafka.consumer.properties`.

**Recommended Configuration Block:**

```yaml
spring:
  kafka:
    consumer:
      group-id: fraud-detection
      enable-auto-commit: false # Standard Spring property
      # Advanced tuning via properties map
      properties:
        fetch.min.bytes: 1024
        fetch.max.wait.ms: 500
      # concurrency: 4 # Keep if 4 threads are desired
```
**File:** `fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/KafkaClient.java`

**Critical Issue:**
The current implementation fires async Cassandra requests (`cqlSession.executeAsync`) but **does not wait for them to complete** before the method returns.
- Because `enable-auto-commit` is true (or even if false, Spring commits on method return), Kafka assumes messages are processed.
- If the application crashes or the DB write fails *after* the method returns, **data is permanently lost**.
- The `Semaphore` logic drops records (`continue`) if busy, causing further data loss.

**Required Changes:**
1.  Enable `manual` acknowledgement or ensure synchronous waiting.
2.  Uncomment and fix the `CompletableFuture.allOf(...).join()` logic.
3.  Remove the `Semaphore` dropping logic; instead, block/wait if the system is overloaded (Backpressure).

**Proposed Implementation Pattern:**

```java
@KafkaListener(
    id = "paymentListener",
    topics = "${kafka.topics.payment.name}",
    concurrency = "${spring.kafka.consumer.concurrency}",
    batch = "true",
    containerFactory = "protobufConcurrentKafkaListenerContainerFactory"
)
public void process(List<ConsumerRecord<byte[], byte[]>> records) {
    long now = System.nanoTime();
    // ... metrics update ...

    List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>(records.size());

    for (var record : records) {
        // Deserialize
        var key = keyDeserializer.deserialize(null, record.key());
        var value = valueDeserializer.deserialize(null, record.value());
        var event = new PaymentEvent(key, value);
        var bound = insertStmt.bind(event.transactionId.getValue(), event.userId.getValue());

        // Execute Async and track future
        futures.add(cqlSession.executeAsync(bound).toCompletableFuture());
    }

    try {
        // WAIT for all DB writes to complete before returning
        // This ensures we don't commit offsets for data not yet persisted
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Log success
    } catch (Exception e) {
        log.error("❌ Batch processing failed. Offsets will NOT be committed.", e);
        throw e; // Throwing exception triggers Spring Kafka retry/backoff
    }
}
```

---

## 4. Broker Tuning (Docker)

**File:** `compose.yaml`

To support the increased batch sizes and throughput:

1.  **Network Threads:** Increase to handle more concurrent connections/requests.
    *   `KAFKA_NUM_NETWORK_THREADS: 5`
2.  **I/O Threads:** Increase for disk writing parallelism.
    *   `KAFKA_NUM_IO_THREADS: 8`
3.  **Socket Buffers:**
    *   `KAFKA_SOCKET_SEND_BUFFER_BYTES: 102400`
    *   `KAFKA_SOCKET_RECEIVE_BUFFER_BYTES: 102400`

---

## Summary of Action Items

1.  **Refactor `payment-service`:** Remove `KafkaConfig.java` and use optimized `application.yaml` (zstd, 64KB batches).
2.  **Fix `fraud-detection-service`:** Update `KafkaClient.java` to wait for async tasks (prevent data loss) and update `application.yaml` (disable auto-commit if using manual Ack, or rely on batch listener exception handling).
3.  **Update `compose.yaml`:** Tune broker thread/buffer settings.
