# architecture

```mermaid
flowchart TD
Client["Client / curl"]

subgraph PS ["PAYMENT-SERVICE"]
  PS1["(2) Generate payment events\n(per-card simulated timeline)"]
  PS2["(10) Action: Live Blocking"]
end

subgraph Kafka ["APACHE KAFKA (KRaft)\n3 controllers + 3 brokers"]
  T1[/"Topic: payment-events\n(9 partitions, RF=3)"/]
  T2[/"Topic: fraud-alerts\n(3 partitions, RF=3)"/]
end

subgraph FD ["FRAUD-DETECTION-SERVICE"]
  FD1["(7) Detection:\nRange query per card\n+ sliding window"]
end

subgraph DB ["SCYLLADB\n3 nodes (RF=3)"]
  DB1[("Table: payment_events_by_card\n(Historical Time-series Data)")]
end

Stats["(12) Shutdown Stats\n(post-hoc ground truth scan)"]

Client -- "1. POST /payment-events" --> PS1
PS1 -- "3. Publish" --> T1
T1 ~~~ T2
FD1 -- "4. Subscribe\n(Ingest all events)" --> T1
FD1 -- "5. Write\n(Bulk Persist)" --> DB1
DB1 -- "6. Read\n(range query per card)" --> FD1
FD1 -- "8. Publish (Alert)" --> T2
PS2 -- "9. Subscribe" --> T2
PS2 -- "11. Output" --> Stats
```

For the detailed fraud detection logic, see [docs/fraud-detection-logic.md](docs/fraud-detection-logic.md).

# procedures

Prerequisites: Docker, Java 25

```zsh
# build JARs, recreate containers, and tail payment-service logs
make up

# (in a separate terminal) tail fraud-detection-service logs
make logs-fraud

# trigger event generation (N=10M)
make post-event n=10000000

# monitor consumer lag at http://localhost:8888 (Kafka UI)
# wait until payment-events topic lag reaches 0 before proceeding

# stop fraud-detection-service and print consumer RPS
make fraud-rps

# stop payment-service and print shutdown stats (confusion matrix, latency, etc.)
make payment-stats
```

# benchmark results

## machine spec

- CPU: Apple M4 Pro, 12 cores (8P + 4E)
- RAM: 48GB

## configuration

- **N**: 10,000,000 events (burst — all published in a single POST request)
- **Fraud rule**: threshold=5, duration=1m (`common/src/main/resources/rules.yaml`)
- **Cards**: 10,000 (`BENCHMARK_CARDS` in `compose.yaml`)
- **Fraud probability**: 0.00004 (`BENCHMARK_FRAUD_PROBABILITY` in `compose.yaml`)
- **Burst multiplier**: 3 (`BENCHMARK_BURST_MULTIPLIER` in `compose.yaml`)
- **Jitter min**: 5s (`BENCHMARK_JITTER_MIN` in `compose.yaml`)
- **Jitter max**: 12h (`BENCHMARK_JITTER_MAX` in `compose.yaml`)

Events use simulated per-card timestamps with jitter between 5s and 12h.
Fraud bursts use variable size: `random[2, threshold * burst-multiplier]` = [2, 15].
Ground truth is computed post-hoc at shutdown via full ScyllaDB scan.

## parameter relationships

```mermaid
flowchart LR
    subgraph params ["Configuration"]
        N["N"]
        C["cards"]
        Pf["Pf"]
        BM["burst-multiplier"]
        T["threshold"]
        D["duration"]
        J["jitter"]
    end

    subgraph derived ["Derived"]
        BS["burst size\nrandom[2, T × BM]"]
        FB["fraud burst count\nN × Pf"]
        CPB["clusters per burst"]
    end

    subgraph metrics ["Metrics"]
        Alerts["expected alerts\nbursts × clusters × recall"]
        P["precision ≈ 1.0"]
        R["recall ≈ 0.94−0.95"]
    end

    T --> BS
    BM --> BS
    N --> FB
    Pf --> FB
    BS --> CPB
    T --> CPB
    FB --> Alerts
    CPB --> Alerts
    R --> Alerts
    J --> P
    D --> P
    BS --> R
    T --> R
```

## results

| N | Cards | Pf | Jitter | Threshold | Duration | Producer RPS | Consumer RPS | Precision | Recall | TP | FP | FN | Latency p50 | Latency p99 |
|---|-------|------|--------|-----------|----------|-------------|-------------|-----------|--------|----|----|----|----|-----|
| 10M | 10K | 0.00004 | 5s-12h | 5 | 1m | 494,715 | 94,068 | 1.0000 | 0.9478 | 1,923 | 0 | 106 | 43,369ms | 85,254ms |
