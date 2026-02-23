# Performance & Throughput Audit Report

This report focuses specifically on identifying bottlenecks and proposing improvements to maximize system throughput (Requests Per Second - RPS) and minimize latency.

## 1. Producer Performance (`payment-service`)

### 1.1. Single-Threaded Event Generation
*   **Current State:** The `PaymentEventsProduceUseCase.run(int n)` method generates and publishes events in a sequential `for` loop on a single thread.
*   **Bottleneck:** Even though `KafkaTemplate.send()` is asynchronous, the CPU cost of generating UUIDs, building Protobuf objects, and serializing them limits the maximum throughput a single thread can achieve.
*   **Recommendation:** Parallelize the event generation using **Java 21 Virtual Threads**.
    *   Split the `n` events into chunks.
    *   Submit chunks to an `ExecutorService` backed by Virtual Threads.
    *   This will utilize all available CPU cores for object creation and serialization.

### 1.2. Kafka Producer Configuration
*   **Current State:**
    *   `batch-size: 16384` (16KB)
    *   `linger-ms: 5`
    *   `compression-type: none` (in `application.yaml`) or `snappy` (if overridden).
*   **Recommendation:**
    *   **Enable Compression:** Set `compression-type: lz4` or `snappy`. This reduces network bandwidth usage and increases throughput at the cost of slight CPU increase.
    *   **Increase Batch Size:** For high throughput, increase `batch-size` to `32768` (32KB) or `65536` (64KB) to reduce the number of requests sent to brokers.

## 2. Consumer Performance (`fraud-detection-service`)

### 2.1. Sequential Processing Loop
*   **Current State:** `KafkaClient.process` iterates through `List<ConsumerRecord>` sequentially. Inside the loop, it performs deserialization and binds CQL statements.
*   **Bottleneck:** Deserialization of Protobuf messages is a CPU-intensive operation. Doing this sequentially adds latency to the batch processing.
*   **Recommendation:** Use **Virtual Threads** to process the batch in parallel.
    ```java
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var record : records) {
            executor.submit(() -> {
                // Deserialize
                // Bind CQL
                // Execute Async
            });
        }
    }
    ```

### 2.2. Semaphore Blocking & Data Loss
*   **Current State:** The `inFlight` semaphore limits concurrency to 32,768. If the semaphore cannot be acquired within 600ms, the record is **dropped** (`continue`).
*   **Impact:** This is a "Fail Fast" mechanism that sacrifices data integrity for stability. In a benchmark, this might show high "throughput" (consumed messages), but the "goodput" (successfully stored messages) drops during congestion.
*   **Recommendation:**
    *   **Backpressure:** Instead of dropping, the consumer should pause (block) until permits are available. This naturally slows down consumption to match DB write speed.
    *   **Rate Limiting:** Ensure `spring.kafka.consumer.max-poll-records` is tuned so that a single batch doesn't overwhelm the semaphore.

### 2.3. Offset Management vs Latency
*   **Current State:** The code submits async DB writes but does **not wait** for them to complete before returning from the `process` method. `enable-auto-commit` is `true`.
*   **Risk:** Offsets are committed before data is safely written to ScyllaDB. If the service crashes, data is lost.
*   **Recommendation for Throughput w/ Safety:**
    *   Disable `enable-auto-commit`.
    *   Wait for all async DB writes in the batch to complete (`CompletableFuture.allOf(...).join()`).
    *   Manually acknowledge (`Ack`) the batch.
    *   *Note:* This will slightly reduce throughput compared to "fire-and-forget", but provides "At Least Once" delivery.

## 3. Database Performance (ScyllaDB)

### 3.1. Schema Design
*   **Current State:** `PRIMARY KEY (transaction_id)`.
*   **Analysis:** Writes are evenly distributed (random UUID). This is good for write throughput as it avoids hot partitions.

### 3.2. Driver Configuration
*   **Current State:** `max-requests-per-connection = 32767`.
*   **Analysis:** This is high and good for throughput, allowing massive parallelism on a single connection.

## 4. Summary of Proposed Changes

| Component | Change | Expected Benefit |
| :--- | :--- | :--- |
| **Producer** | Parallelize `run(n)` with Virtual Threads | **2x - 5x** increase in event generation rate. |
| **Producer** | Enable `lz4` compression | Reduced network I/O, higher throughput. |
| **Consumer** | Parallelize `process` loop with Virtual Threads | Faster batch processing, better CPU utilization. |
| **Consumer** | Implement Wait + Manual Ack | Data safety (prevents data loss on crash). |
| **Consumer** | Remove "Drop on Semaphore Timeout" | Data integrity (zero data loss). |

These changes transform the system from a "fast but lossy" pipeline to a "fast and reliable" high-performance streaming architecture.
