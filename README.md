# fraudx

[ Client / curl ]
                |
                | 1. POST /payment-events
                v
      +-------------------------+
      | payment-service         |
      | (REST API / Producer)   |
      +---------+---------------+
                |
                | 2. Publish (Protobuf)
                v
+---------------------------------------+         +-------------------+
| Apache Kafka (KRaft Mode)             |<--------| Kafka UI (8888)   |
|                                       | Monitor +-------------------+
|      (( Topic: payment-events ))      |
|                   |                   |
|                   v                   |
|     [ 3x Brokers (broker-1,2,3) ]     |
|                   |                   |
|                   v                   |
|  [ 3x Controllers (controller-1,2,3)] |
+-------------------+-------------------+
                |
                | 3. Subscribe (Batch)
                v
      +-------------------------+
      | fraud-detection-service |
      | (Consumer / DB Client)  |
      +---------+---------------+
                |
                | 4. Async Bulk Insert (CQL)
                v
      +-------------------------+
      | ScyllaDB Cluster        |
      | - 3x Nodes              |
      | - Keyspace: fraudx      |
      | - Table: payment_events |
      +-------------------------+

# Detection rules
1. velocity/frequency (same card/device_id used M times in N min)
    - ScyllaDB TTL (Time To live) + Time Window Compaction Strategy (TWCS)
1. Transactional pattern deviation (amount deviates 3σ from user mean)
    - ScyllaDB Counter Column


# Performance on Feb 12, 2026
- publisher: 0.7 M
- subscriber: 0.1 M


# procedures
```zsh
make up
# make up-metrics

make logs-fraud

make post-event n=10000000

make fraud-rps

make cql
```




# TODO
- [ ] [payment service] fraudulent payment event 実装
    - [ ] 動的にmessage内容を変え、memoryにIDを保持
    - [ ] 不正決済イベントIDをlistで返すAPI 実装
- [ ] [fraud detection service] detection logic 実装
- [ ] [fraud detection service]  payment service に API call (精度計算のため)



