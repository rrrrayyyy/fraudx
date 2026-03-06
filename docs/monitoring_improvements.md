# Monitoring Improvements Plan

This document outlines the current state of monitoring in the `fraudx` system and provides a comprehensive guide to adding necessary metrics for performance tuning and operational visibility.

## 1. Current State Assessment

### Infrastructure (Docker Compose)
*   **Prometheus:** Scrapes targets defined in `monitoring/prometheus/prometheus.yml`.
*   **JMX Exporter:** Attached to Kafka Brokers and Controllers via `metrics-compose.yaml` to expose JMX metrics to Prometheus.
*   **ScyllaDB:** Metrics are exposed by ScyllaDB natively on port 9180 (Prometheus format).
    *   **Note:** ScyllaDB containers are on `fraudx-net`. Prometheus must be on the same network to scrape them. Currently `metrics-compose.yaml` uses `kafka-net`, which causes a network isolation issue.
*   **Application:** Spring Boot Actuator/Micrometer is required for application-level metrics.

### Missing Metrics (Critical Gaps)

| Component | Missing Metric Category | Impact |
| :--- | :--- | :--- |
| **Kafka (Client)** | Consumer Lag | Cannot detect if consumers are falling behind producers. (Must be measured at Application/Client side) |
| **Kafka (Broker)** | Request Latency | Cannot measure broker performance bottlenecks. |
| **ScyllaDB** | **ALL** | No visibility into database performance (latency, compaction, cache). |
| **Application** | Custom Business Metrics | No tracking of "Fraud Detected" count or "Payment Processed" count. |
| **Application** | Processing Latency | No visibility into how long `process()` takes per batch. |

## 2. Recommended Metrics for Performance Tuning

To effectively tune throughput and latency, the following metrics must be collected:

### 2.1. Kafka (JMX Exporter - Broker Side)
Ensure `kafka-config.yaml` captures these specific MBeans from the Broker:

*   **Throughput:** `kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec`
*   **Throughput:** `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec`
*   **Disk Usage:** `kafka.log:type=Log,name=Size,topic=*,partition=*`
*   **KRaft Controller:** `kafka.controller:type=KafkaController,name=ActiveControllerCount` (To verify leadership)

### 2.2. Kafka (Client Side - Application)
These metrics are available via Micrometer in the Java application, NOT the broker JMX:

*   **Consumer Lag:** `kafka.consumer:type=consumer-fetch-manager-metrics,name=records-lag-max`

### 2.3. ScyllaDB (Native Prometheus)
ScyllaDB exposes metrics at `:9180/metrics`. Add this job to `prometheus.yml`:

```yaml
  - job_name: 'scylladb'
    static_configs:
      - targets:
          - 'scylladb-1:9180'
          - 'scylladb-2:9180'
          - 'scylladb-3:9180'
```

**Key Metrics to Watch:**
*   `scylla_cql_latency_summary`: P99 Latency of CQL requests.
*   `scylla_storage_proxy_coordinator_read_latency`: Read latency.
*   `scylla_storage_proxy_coordinator_write_latency`: Write latency.
*   `scylla_compaction_manager_compactions_pending`: Backlog of compaction tasks.

### 2.4. Application (Micrometer)
Add custom instrumentation in Java code:

*   **Fraud Detection Rate:** `Counter` incremented when fraud is detected.
*   **Processing Time:** `Timer` wrapping the `process()` method in `KafkaClient`.
*   **Semaphore Wait Time:** `Timer` measuring how long threads wait for `inFlight` permits.

## 3. Implementation Guide (How to Add)

### Step 1: Fix Network Configuration
Modify `metrics-compose.yaml` to use `fraudx-net` instead of `kafka-net` to ensure Prometheus can reach ScyllaDB and Kafka brokers.

```yaml
networks:
  fraudx-net:
    external: true  # Or define it if not external, but must match compose.yaml
```

### Step 2: Update `prometheus.yml`
Add the ScyllaDB scrape config shown above. Note that targets must be resolvable within the docker network.

### Step 3: Update `kafka-config.yaml`
The current pattern `kafka.server<type=(.+), name=(.+)><>(Value)` is generic but might miss specific attributes like `Count` or `OneMinuteRate`.
Add explicit rules for high-value metrics:

```yaml
rules:
  - pattern: kafka.server<type=BrokerTopicMetrics, name=MessagesInPerSec, topic=(.+)><>OneMinuteRate
    name: kafka_messages_in_per_sec
    labels:
      topic: "$1"
```

### Step 4: Java Implementation (Example)

**Prerequisites:**
Add the following dependencies to `build.gradle` (Fraud and Payment services):
*   `implementation 'org.springframework.boot:spring-boot-starter-actuator'`
*   `implementation 'io.micrometer:micrometer-registry-prometheus'`

**Inject MeterRegistry:**
```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;

private final Timer processingTimer;
private final Counter fraudCounter;

public KafkaClient(MeterRegistry registry, ...) {
    this.processingTimer = registry.timer("fraud.processing.time");
    this.fraudCounter = registry.counter("fraud.detected");
}
```

**Record Metrics:**
```java
public void process(...) {
    processingTimer.record(() -> {
        // ... existing logic ...
    });
}
```

## 4. Dashboarding (Grafana)
Once metrics are flowing, create a dashboard with:
1.  **System Throughput:** Sum of `kafka_messages_in_per_sec`.
2.  **E2E Latency:** `scylla_cql_latency` + `fraud_processing_time`.
3.  **Backpressure:** Semaphore available permits vs. max permits.

### 4.1. Prometheus Service Discovery
*   **現状:** `prometheus.yml` に `controller-1`, `broker-1` などのホスト名が静的に記述されています。
*   **改善案:** Dockerのスケーリングに対応するため、Prometheusの `dns_sd_configs`（DNS Service Discovery）を使用するか、Docker Composeのサービス名解決に依存しない動的な検出設定を検討します。

### 4.2. Grafana Provisioning
*   **現状:** Grafanaの設定が含まれていません（`metrics-compose.yaml` にも見当たりません）。
*   **改善案:** Grafanaコンテナを追加し、ダッシュボードとデータソースをコード管理（Provisioning）することで、可視化環境を即座に立ち上げられるようにします。
