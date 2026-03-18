# fraudx architecture

[ Client / curl ]
        |
        | 1. POST /payment-events (Request)
        v
+-----------------------------------------------------+
| [ PAYMENT-SERVICE ]                                 |
| (2) Labeling: Store "Ground Truth" (Mock vs Normal) |
| (10) Action: Live Blocking & Shutdown Stats Summary | <---+
+-------+---------------------------------------------+     |
        |                                                   |
        | 3. Publish (Topic: payment-events)                | 9. Subscribe (Topic: fraud-alerts)
        v                                                   |
+-----------------------------------------------------------+-----+
| [ APACHE KAFKA ]                                                |
| - payment-events (All transaction traffic)                      |
| - fraud-alerts (Detected malicious user IDs)                    |
+-------+---------------------------------------------------+-----+
        |                                                   ^
        | 4. Subscribe (Ingest all events)                  | 8. Publish (Alert)
        v                                                   |
+-----------------------------------------------------------+-----+
| [ FRAUD-DETECTION-SERVICE ]                                     |
| (7) Detection: Calculate Fraud Score via Logic                  |
+-------+---------------------------------------------------+-----+
        |                                                   ^
        | 5. Write (Bulk Persist)                           | 6. Read (History Fetch)
        v                                                   |
+-----------------------------------------------------------+-----+
| [ SCYLLADB ]                                                    |
| - Tables: payment_events (Historical Time-series Data)          |
+-----------------------------------------------------------------+


# Detection rules
1. velocity/frequency (same card/device_id used M times in N min)
    - ScyllaDB TTL (Time To live) + Time Window Compaction Strategy (TWCS)
1. Transactional pattern deviation (amount deviates 3σ from user mean)
    - ScyllaDB Counter Column

# procedures
```zsh
make up
make logs-fraud

make post-event n=10000000

make fraud-rps

make cql
```


# TODO
- [ ] [payment service] fraudulent payment event 実装
    - 動的にmessage内容を変え、memoryにIDを保持
- [ ] [fraud detection service] detection logic 実装
- [ ] [payment service + fraud detection service] payment service に API call (精度計算のため)



