# fraudx

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



