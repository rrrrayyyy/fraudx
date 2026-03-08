# Strict 1M RPS Optimization Plan: Comprehensive Analysis

## 4. Unconstrained Optimization: Breaking the Premise

### 4.1. Replace Kafka with Redpanda (C++)
*   **Why:** Kafka (JVM) has significant GC and object overhead. Redpanda is a C++ drop-in replacement that uses a thread-per-core architecture (Seastar), matching ScyllaDB's design.
*   **Impact:** 10x-50x lower latency and higher throughput on the same hardware. Zero GC tuning required.
*   **Effort:** Minimal. Just change the Docker image in `compose.yaml`.
