# Strict 1M RPS Optimization Plan: Comprehensive Analysis

This document provides a rigorous, deep-dive analysis of all bottlenecks preventing the system from achieving 1M RPS, covering application logic, database routing, and low-level system tuning.

## 1. Application-Level Bottlenecks

### 1.1. Producer Parallelism (`payment-service`)
**Finding:** `PaymentEventsProduceUseCase.java` uses a simple `for` loop on a single thread.
```java
public void run(int n) {
    for (int i = 0; i < n; i++) {
        // ... key/value generation ...
        paymentEventProducer.publish(key, value);
    }
}
```
**Impact:** A single Java thread cannot generate and serialize 1M complex objects per second due to CPU saturation (Protobuf serialization, object allocation).
**Correction:** **MANDATORY** parallelization using Virtual Threads is required to saturate the CPU and Kafka Producer buffer.

### 1.2. Consumer Sequential Processing (`fraud-detection-service`)
**Finding:** The `process` loop in `KafkaClient` is sequential.
```java
for (var record : records) {
    // deserialize (CPU heavy)
    // bind (CPU light)
    // executeAsync (IO)
}
```
**Impact:** The total batch processing time is `Sum(Deserialize + Bind) + Max(AsyncWait)`. At 1M RPS, deserialization of 5000 records sequentially adds significant latency.
**Correction:** Use Virtual Threads for parallel deserialization and processing within the batch.

### 1.3. ScyllaDB Token-Aware Routing Failure (CRITICAL)
**Finding:** `KafkaClient.java` creates a `SimpleStatement` (via `insertStmt.bind(...)`) but **does NOT set the Routing Key**.
```java
var bound = insertStmt.bind(event.transactionId.getValue(), event.userId.getValue());
// Missing: bound.setRoutingKey(...) or setRoutingToken(...)
```
**Impact:** The DataStax driver cannot determine which ScyllaDB node holds the data. Requests are sent to random coordinator nodes, doubling network hops and increasing CPU load on ScyllaDB.
**Correction:** Explicitly set the routing key or use a TokenAware-compatible prepared statement binding. Ensure `basic.load-balancing-policy.class = DefaultLoadBalancingPolicy` is active in `application.conf`.

### 1.4. Inefficient Flow Control & Reliability
**Finding:**
*   `inFlight` Semaphore drops records on timeout (Data Loss).
*   `cqlSession.executeAsync` is not properly joined (Offset commit race condition).
**Correction:**
*   Remove Semaphore dropping logic.
*   Use `CompletableFuture.allOf(...).join()` at the end of the batch for natural backpressure and zero data loss.

## 2. Low-Level System Tuning

### 2.1. Kernel & Network Tuning (Docker Compose)
**Finding:** `compose.yaml` does not define any kernel parameters (`sysctls`).
**Impact:** At 1M RPS, default Linux network limits will be hit immediately:
*   `net.core.somaxconn`: Connection backlog overflow.
*   `net.ipv4.tcp_tw_reuse`: Ephemeral port exhaustion.
*   `vm.max_map_count`: Critical for Kafka/Scylla stability.

**Recommendation:** Add `sysctls` to `compose.yaml`:
```yaml
    sysctls:
      - net.core.somaxconn=65535
      - net.ipv4.tcp_tw_reuse=1
      - net.ipv4.ip_local_port_range=1024 65535
```

### 2.2. Logging Bottlenecks (`logback-spring.xml`)
**Finding:** `AsyncAppender` wrapping `ConsoleAppender` is used.
**Impact:** `ConsoleAppender` writes to `System.out` (Docker pipe). High volume logging blocks the application when the pipe buffer fills.
**Correction:**
*   Disable Console logging or set root level to `WARN` for performance tests.
*   Remove `FILE` appender in Docker (slow overlayfs writes).
*   Disable `scan="true"` to stop background config monitoring threads.

### 2.3. JVM & Garbage Collection
**Finding:**
*   Kafka Brokers: Heap limited to 2GB (`-Xmx2g`). Too small for 1M RPS.
*   Microservices: No JVM flags defined (defaults to G1GC).
**Recommendation:**
*   **Kafka:** Increase Broker Heap to 4GB+.
*   **Microservices:** Explicitly enable **ZGC** (`-XX:+UseZGC`) for low-latency processing on Java 25. Set fixed heap size (e.g., `-Xms2g -Xmx2g`).

### 2.4. ScyllaDB & Kafka Server Tuning
**Finding:**
*   `scylla.yaml`: `write_request_timeout_in_ms` is set to 180000 (3 minutes). This hides failures instead of failing fast, causing client queues to fill up.
*   `compose.yaml` (Kafka): `KAFKA_NUM_NETWORK_THREADS=5` is set, but `KAFKA_NUM_IO_THREADS` is missing (default 8). For NVMe/SSD, this should be increased (e.g., 16). `socket.send.buffer.bytes` and `socket.receive.buffer.bytes` are defaults (100KB), which is too small for 1M RPS.
**Recommendation:**
*   **ScyllaDB:** Reduce timeouts to fail fast (e.g., 2000ms).
*   **Kafka:** Add `KAFKA_SOCKET_SEND_BUFFER_BYTES=1048576` (1MB) and `KAFKA_SOCKET_RECEIVE_BUFFER_BYTES=1048576`. Increase `KAFKA_NUM_IO_THREADS=16`.

## 3. Infrastructure & Environment Limits

### 3.1. Docker Desktop on macOS
**Reality Check:** Achieving 1M RPS on Docker Desktop for Mac is physically difficult due to virtualization overhead (network bridge, filesystem).
**Correction:**
*   **Development:** Use **OrbStack** for better performance.
*   **Production/Test:** Benchmark on native Linux.

## 4. Unconstrained Optimization: Breaking the Premise

What if we ignore the current constraints (Java, Spring Boot, Docker Desktop)? How easily can 1M RPS be achieved by changing the fundamental architecture?

### 4.1. Replace Kafka with Redpanda (C++)
*   **Why:** Kafka (JVM) has significant GC and object overhead. Redpanda is a C++ drop-in replacement that uses a thread-per-core architecture (Seastar), matching ScyllaDB's design.
*   **Impact:** 10x-50x lower latency and higher throughput on the same hardware. Zero GC tuning required.
*   **Effort:** Minimal. Just change the Docker image in `compose.yaml`.

### 4.2. Rewrite Microservices in Rust
*   **Why:**
    *   **Zero GC:** No "Stop-the-World" pauses at 1M RPS.
    *   **Memory Safety:** No heap exhaustion risks.
    *   **Performance:** Native binaries with minimal runtime overhead.
*   **Impact:** A single Rust consumer instance (using `rdkafka` and `scylla-rust-driver`) can easily handle 1M RPS on a modern laptop, whereas Java requires careful tuning and multiple instances.
*   **Effort:** High (Rewrite code), but drastically reduces operational complexity (smaller footprint, no JVM tuning).

### 4.3. Bypass Kafka (Direct gRPC)
*   **Why:** If durability/buffering is not strictly required for *all* events, `payment-service` could stream directly to `fraud-detection-service` via gRPC (HTTP/2) or UDP (Aeron).
*   **Impact:** Eliminates the broker hop entirely. Network I/O is halved.
*   **Effort:** Medium. Requires handling backpressure and retries manually.

### 4.4. Batch Insert at Source (ScyllaDB Optimized)
*   **Why:** If the goal is just "Store Data Fast", `payment-service` could write directly to ScyllaDB using the Shard-Aware Rust Driver, skipping the microservice hop entirely.
*   **Impact:** Maximum theoretical write throughput (limited only by disk I/O).
*   **Effort:** Medium. Changes system topology.

### 4.5. Conclusion: The "Easy" Path
If 1M RPS is the *only* goal:
1.  **Use Redpanda** instead of Kafka.
2.  **Write a Rust Consumer** that reads from Redpanda and writes to ScyllaDB using the Seastar-based drivers.
3.  **Run on Linux (Metal)**, bypassing Docker networking.

This stack would achieve 1M RPS with a fraction of the CPU/Memory resources required by the current Java/Spring/Kafka setup.

## 5. Consolidated Action Plan

| Priority | Area | Action |
| :--- | :--- | :--- |
| **P0** | **App** | Parallelize Producer & Consumer with **Virtual Threads**. |
| **P0** | **DB** | Fix ScyllaDB **Token-Aware Routing** (set Routing Key). |
| **P0** | **Rel** | Implement proper **Backpressure** (Wait for Batch) & Zero Data Loss. |
| **P1** | **Kernel** | Apply `sysctls` (somaxconn, tcp_tw_reuse) in `compose.yaml`. |
| **P1** | **JVM** | Enable **ZGC** and tune Heap sizes. |
| **P1** | **Kafka** | Increase Socket Buffers (1MB) & IO Threads (16). |
| **P2** | **Logs** | Disable Console/File logging, set level to WARN. |
| **P2** | **Env** | Move benchmark to Linux/OrbStack. |
