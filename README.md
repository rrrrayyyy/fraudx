# fraudx architecture

```mermaid
flowchart TD
Client["Client / curl"]

subgraph PS ["PAYMENT-SERVICE"]
  PS1["(2) Labeling: Store Ground Truth\n(Mock vs Normal)"]
  PS2["(10) Action: Live Blocking"]
end

subgraph Kafka ["APACHE KAFKA"]
  T1[/"Topic: payment-events\n(All transaction traffic)"/]
  T2[/"Topic: fraud-alerts\n(Detected malicious user IDs)"/]
end

subgraph FD ["FRAUD-DETECTION-SERVICE"]
  FD1["(7) Detection:\nCalculate Fraud Score via Logic"]
end

subgraph DB ["SCYLLADB"]
  DB1[("Table: payment_events\n(Historical Time-series Data)")]
end

Stats["(11) Shutdown Stats Summary"]

Client -- "1. POST /payment-events" --> PS1
PS1 -- "3. Publish" --> T1
T1 ~~~ T2
FD1 -- "4. Subscribe\n(Ingest all events)" --> T1
FD1 -- "5. Write\n(Bulk Persist)" --> DB1
DB1 -- "6. Read\n(History Fetch)" --> FD1
FD1 -- "8. Publish (Alert)" --> T2
PS2 -- "9. Subscribe" --> T2
PS2 -- "11. Output" --> Stats
```



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



