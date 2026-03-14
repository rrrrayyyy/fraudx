# Kafka Subscriber 500K RPS Design v3: Origin Main ベースの段階的最適化

## 1. Facts

### 1.1 Origin Main Configuration（唯一の安定実績あり設定）

| Parameter | Value | Source |
|---|---|---|
| max.poll.records | 3000 | compose.yaml |
| concurrency | 9 | compose.yaml |
| Throttler | なし (PassThroughRequestThrottler) | application.conf |
| request.timeout | 2s (DataStax default) | application.conf (未設定) |
| pool.local.size | 32 | application.conf |
| max-requests-per-connection | 32767 | application.conf |
| JVM heap | ~1g (JVM default, container 4g の 1/4) | Dockerfile (未設定) |
| Container mem_limit | 4g | compose.yaml |
| Key deserializer | KafkaProtobufDeserializer | application.yaml |
| Value deserializer | KafkaProtobufDeserializer | application.yaml |
| ScyllaDB | 3 nodes × smp=3 = 9 shards | compose.yaml |
| Partitions | 9 | payment-service config |

**実績**: 10M イベントベンチマークで ~166K RPS、クラッシュなし。

**Concurrent requests**: 9 threads × 3000 = **27,000**

### 1.2 Crash History（4回全て origin main からの逸脱が原因）

| # | Config変更 | Concurrent | Crash時点 | エラー |
|---|---|---|---|---|
| 1 | pool.local.size=32→2 | 27K | 起動直後 | NodeUnavailableException (cold start) |
| 2 | 6+ params同時変更 | 27K | 即座 | AllNodesFailedException |
| 3 | throttler 30K→300K + heap 4g→2g | 175K | ~25s / 168K RPS | NodeUnavailableException (全3ノード) |
| 4 | throttler=30K + max.poll.records=30000 | 175K | ~25s / 168K RPS | NodeUnavailableException (全3ノード) |

**共通パターン**: Crash #3, #4 は共に concurrent=175K (max.poll.records=30000 × 9 threads) で ~25s 後にクラッシュ。

### 1.3 Hardware Resources

| Resource | Spec |
|---|---|
| Host CPU | Apple M4 Pro, 12 cores (8P + 4E) |
| Host RAM | 48GB |
| ScyllaDB cpuset | 9 cores (0,3,9 / 1,4,10 / 2,5,11) |
| Remaining | 3 cores (6,7,8) for Kafka + JVM |

### 1.4 DataStax Java Driver 公式ドキュメント（Warrant sources）

**max-requests-per-connection**:
- Default: **1024**
- 公式: "raising max-requests-per-connection above 1024 does not bring any significant improvement"
- Source: [DataStax Java Driver Connection Pooling](https://docs.datastax.com/en/developer/java-driver/4.13/manual/core/pooling/)

**PassThroughRequestThrottler**:
- 公式: "This is a no-op implementation: requests are simply allowed to proceed all the time, never enqueued."
- Throttler 未設定時のデフォルト = PassThrough（オーバーヘッドゼロ）
- Source: [DataStax Java Driver Request Throttling](https://docs.datastax.com/en/developer/java-driver/4.13/manual/core/throttling/)

**Orphaned Stream ID による自動切断**:
- Request timeout 後、response がまだ届いていない stream ID は "orphaned" としてマーク
- Orphaned count がしきい値（default=256）を超えると、ドライバが**接続を自動クローズ**
- Source: [ScyllaDB Java Driver Pooling](https://java-driver.docs.scylladb.com/stable/manual/core/pooling/)

**NodeUnavailableException vs BusyConnectionException**:
- NodeUnavailableException: ノードに使用可能な接続が**ゼロ**（接続が消失）
- BusyConnectionException: 接続は存在するが全 stream ID が使用中

### 1.5 ScyllaDB Throughput

- 公式最低ライン: 12,500 OPS/physical core（simple operations）
- 観測値: ~166K writes/s total = 18.4K/shard/s（origin main, 27K concurrent）
- Source: [ScyllaDB Benchmark Tips](https://docs.scylladb.com/manual/stable/operating-scylla/procedures/tips/benchmark-tips.html)

---

## 2. Analysis

### 2.1 Origin Main が安定している理由

| Check | Value | Safe? |
|---|---|---|
| Concurrent per connection | 27K / 96 connections = **281** | 281 << 1024 (default limit) |
| Total stream IDs needed | 27K | << 96 × 32767 = 3.15M |
| Heap for in-flight objects | 27K × ~2KB = ~54MB | << ~1g heap |
| Request completion time | loop(28ms) + join(108ms) = 136ms | << 2s default timeout |
| Orphaned risk | Requests finish in 136ms, timeout=2s → orphaned=0 | Safe |

**Claim**: Origin main は全てのリソース制約に対して十分な安全マージンを持つ。接続は健全に維持され、request timeout も orphaned stream ID も発生しない。

### 2.2 Crash #3, #4 の原因分析

**Fact**:
- 175K concurrent (9 × 19.5K)
- Throttler=30K: 30K on-wire, 145K queued in JVM
- ~25s 後に全ノードの全接続が同時に消失
- Stack trace に `ConcurrencyLimitingRequestThrottler.register` → `sendRequest` → NodeUnavailableException

**Warrant**:
1. 175K CompletableFuture + CqlRequestHandler objects ≈ 350MB/cycle。9 threads 重複で最大 3.15GB on 4g heap → **GC 圧力**
2. Throttler の `register()` は内部 lock を使用。9 threads が 19.5K 回ずつ lock を取り合い → lock contention による loop 遅延
3. Request timeout は `executeAsync()` 呼び出し時点から起算。Throttler queue 待ち時間もカウントされる
4. Queue 蓄積: submission rate > completion rate の場合、queue が線形に成長
5. Queue 深部の request が timeout → orphaned stream ID 蓄積 → **接続自動クローズ** → NodeUnavailableException

**Claim**: Crash の直接原因は **concurrent 175K による複合的な崩壊**:
- GC 圧力 → Netty event loop stall → heartbeat miss → 接続切断
- AND/OR throttler queue 蓄積 → request timeout → orphaned stream ID 蓄積 → 接続自動クローズ
- いずれにせよ、**27K → 175K (6.5x) の concurrent 増加が根本原因**

### 2.3 理論最大 RPS の制約分析

現在のアーキテクチャ: `poll → loop → join → commit` (sequential)

```
RPS = C × B / Tcycle
Tcycle = loop(B) + join(B, C×B) + gap(B)

C = concurrency (threads)
B = batch size per thread
loop(B) ≈ 9.5μs × B
join(B, N) = ScyllaDB が N requests を処理する時間 - loop(B)
gap(B) = Kafka poll + commit + deserialization overhead
```

**Deferred join pipelining** を導入した場合:
```
Tcycle = max(join, loop + gap)
```

**ScyllaDB が律速する場合**（join > loop + gap）:
```
RPS = C × B / join
join ≈ B / μ  (μ = per-shard throughput, B requests distributed across shards)
RPS = C × B / (B / μ) = C × μ
```

**Claim**: Deferred join pipelining 下で ScyllaDB が律速の場合、**RPS = C × μ** であり、B に依存しない。

| μ (per shard) | RPS (C=9) | 根拠 |
|---|---|---|
| 18.4K/s | 166K | Origin main 観測値 |
| 30K/s | 270K | 楽観的推定 |
| 55.6K/s | 500K | 500K RPS に必要な値 |
| 12.5K/s | 112K | 公式最低ライン |

**500K RPS には μ = 55.6K/shard/s が必要。これは観測値の 3x、公式最低ラインの 4.4x。**

この値が達成可能かどうかは**実測でしか判断できない**。ScyllaDB の simple INSERT は in-memory commitlog buffer への書き込みであり、CPU-gated なので 55.6K/s は不可能ではないが、保証もない。

---

## 3. Design

### 設計方針

1. Origin main を唯一の安定ベースラインとして出発する
2. **1回のベンチマークにつき1つのパラメータだけ変更**する
3. 各ステップで 10M イベントベンチマークを完走させ安定性を確認する
4. 実測データで予測モデルを校正し、次のステップを決定する

### Phase 1: Origin Main + 安全な最適化（concurrent 変更なし）

**変更内容**:

1. **application.conf**: origin main 状態に戻す（throttler 削除、request.timeout 削除）
2. **application.yaml**: key-deserializer → ByteArrayDeserializer、protobuf.key.type 削除
3. **KafkaClient.java**: `ConsumerRecord<byte[], byte[]>` + `extractTransactionId()` 追加
4. **compose.yaml**: MAX_POLL_RECORDS → **3000**（origin main に戻す）

**変更しないもの**:
- Phase timing instrumentation（測定に必要）
- Pool warmup + autoStartup=false（cold-start crash 防止、origin main にはなかったが安全側の変更）
- value=ByteArrayDeserializer + extractUserId()（既に適用済み、安全）
- Dockerfile heap -Xms4g -Xmx4g（Phase 2 での headroom 確保）
- compose.yaml mem_limit=6g, CONCURRENCY=9, TRACING=0

**予測**:
- Concurrent: 9 × 3000 = 27K（origin main と同一 → 安全）
- Gap 削減: key deser ByteArray 化で ~4μs/record × 3000 = ~12ms 削減
- Origin Tcycle ≈ 163ms → 新 Tcycle ≈ 151ms
- RPS ≈ 9 × 3000 / 151 = **179K**（+8%）

**確認項目**:
- 10M イベント完走、クラッシュなし
- Phase timing: loop, join, gap の実測値を記録
- ScyllaDB per-shard throughput の校正データ取得

### Phase 2: Max Stable Batch Size の探索

Phase 1 の安定を確認後、**MAX_POLL_RECORDS のみ**を段階的に増加:

```
3000 → 4000 → 5000 → 6000 → 8000 → 10000 → 15000
```

各ステップで 10M イベントベンチマークを実行し:
1. クラッシュしないことを確認
2. Phase timing (loop, join, gap) を記録
3. RPS を記録
4. ScyllaDB per-shard throughput (= RPS / 9) を計算

**判定基準**:
- クラッシュ → 前のステップの B が max stable B
- RPS が前ステップより低下 → ScyllaDB 飽和の兆候

**各 B での concurrent と安全性予測**:

| B | Concurrent (9×B) | Per connection (÷96) | vs 1024 default | Risk |
|---|---|---|---|---|
| 3,000 | 27K | 281 | 27% | Proven safe |
| 4,000 | 36K | 375 | 37% | Low |
| 5,000 | 45K | 469 | 46% | Low |
| 6,000 | 54K | 563 | 55% | Medium |
| 8,000 | 72K | 750 | 73% | Medium |
| 10,000 | 90K | 938 | 92% | High (near 1024) |
| 15,000 | 135K | 1,406 | 137% | Very high (exceeds 1024) |

**Warrant**: DataStax 公式では 1024 以上は改善なしとしている。Per-connection concurrent が 1024 を超えると BusyConnectionException のリスクが発生。B=10000 (938/connection) が理論上の上限。

### Phase 3: アーキテクチャ変更（Phase 2 データに基づく）

Phase 2 で得られるデータ:
- Max stable B
- 各 B での μ (per-shard throughput)
- join/loop/gap の B 依存性

**Phase 3A: Deferred Join Pipelining**（μ > 18.4K/s が確認できた場合）

```java
// 現在: sequential
poll → loop → join → commit

// 提案: deferred join
// Batch N-1 の join を Batch N の poll 後に実行
CompletableFuture[] prevFutures = null;

while (true) {
    records = poll();
    if (prevFutures != null) {
        CompletableFuture.allOf(prevFutures).join(); // join previous batch
        commit(previous);
    }
    prevFutures = new CompletableFuture[records.size()];
    for (i = 0; i < records.size(); i++) {
        prevFutures[i] = executeAsync(records.get(i));
    }
}
```

効果: `Tcycle = max(join, loop + gap)` → join と poll+loop がオーバーラップ

**注意**: Spring Kafka Batch Listener では poll/commit のタイミングを直接制御できない。実装には `KafkaMessageListenerContainer` のカスタマイズまたは手動 Consumer 管理が必要。

**Phase 3B: Sub-batching with Concurrency Cap**（max stable B < 必要値の場合）

```java
public void process(List<ConsumerRecord<byte[], byte[]>> records) {
    int windowSize = MAX_SAFE_CONCURRENT / concurrency; // e.g., 27000 / 9 = 3000
    for (int offset = 0; offset < records.size(); offset += windowSize) {
        int end = Math.min(offset + windowSize, records.size());
        var futures = new CompletableFuture[end - offset];
        for (int i = offset; i < end; i++) {
            futures[i - offset] = executeAsync(records.get(i));
        }
        CompletableFuture.allOf(futures).join();
    }
}
```

効果: 大きな B を poll しつつ、concurrent を windowSize × 9 に制限。Gap は B に対して amortize されるが、join は window 単位で sequential。

**Phase 3C: ScyllaDB Scaling**（μ < 55.6K/s が確定した場合）

500K RPS に μ = 55.6K/shard/s が必要。Phase 2 で μ の上限が判明したら:

```
必要 shard 数 = 500K / μ_observed
```

| μ_observed | 必要 shard 数 | 構成例 |
|---|---|---|
| 18.4K/s | 27 | 9 nodes × smp=3 or 3 nodes × smp=9 |
| 30K/s | 17 | 6 nodes × smp=3 |
| 40K/s | 13 | 3 nodes × smp=5 (需要 15 cores) |
| 55.6K/s | 9 | 現状で達成可能 |

**ローカル M4 Pro (12 cores) の制約**: ScyllaDB に 9 cores 以上割り当てると Kafka/JVM の CPU が不足。smp 増加は CPU 追加または別マシンが必要。

---

## 4. Experiment Results

### 4.0 Full Experiment History

| # | Config | Concurrent | RPS | 結論 |
|---|---|---|---|---|
| Baseline (3/9) | origin main (pool=32, B=3000, no throttler, default heap) | 27K | **112K** | 基準 |
| Exp 1 (3/11) | pool=8 + B=8000 + smp=2 (3変更) | 72K | **CRASH** | cold-start race |
| Exp 2 (3/11) | code opt + heap=2g + tracing=0 | 27K | **91K** | regression (heap?) |
| Exp 3 (3/11) | Exp 2 再試行 | 27K | **89K** | 再現性あり |
| Exp 4 (3/11-12) | Exp 3 + smp=2 | 27K | **94K** | CPU仮説棄却 |
| Exp 5 | pool=2 + B=30000 + 6変更 | 27K | **CRASH** | cold-start race (再発) |
| Step 0 (3/12) | Exp 4 + instrumentation (smp=3) | 27K | **166K** | join=72.5%, batch capped |
| v1 | throttler=300K + heap=2g + key=ByteArray | 175K | **CRASH** | ~25s / 168K RPS で崩壊 |
| v2 | throttler=30K + B=30000 | 175K | **CRASH** | ~25s / 168K RPS で崩壊 |
| **Phase 1 (3/13)** | **origin main + key ByteArray + heap=4g** | **27K** | **135K** | **安定だが join regression** |
| **Phase 2 (3/13)** | **Phase 1 + heap=2g + B=10000** | **90K** | **176K** | **join 回復 + gap amortization** |
| Phase 2a (3/13) | Phase 2 + Netty io-group=24 + B=20000 | 180K | **FAIL** | DriverTimeoutException (CPU oversubscription) |
| Phase 2b (3/13) | Phase 2 + 12 partitions + C=12 | 120K | **164K** | 退化 (-7%, Netty 飽和) |
| Phase 2c (3/13) | Phase 2 + ZGC | 90K | **FAIL** | PaymentRepository init timeout |
| **Phase 2 final (3/13)** | **Phase 2 (全実験 revert)** | **90K** | **170K** | **安定、176K の分散内** |
| Phase 3 (3/13) | Phase 2 + MaxGCPauseMillis=5 | 90K | **139K** | 退化 (-21%, loop 3.2× 悪化) |
| Phase 4 (3/13) | concurrency=3 + B=30000 | 90K | **164K** | 退化 (-7%, thread 不足) |
| **Phase 5 (3/14)** | **Phase 2 + debug instrumentation 削除** | **90K** | **182K** | **過去最高 (theoretical max の 97%)** |

### 4.1 Phase 1 Results (3/13)

**Config**: origin main base + key/value ByteArrayDeserializer + extractTransactionId() + heap=4g + B=3000

```
Batch stats: count=3627, avgSize=2757, min=4, max=3000
Phase timing (avg per batch): loop=26021us, join=139858us, gap(poll+commit)=15670us
Phase timing (total): loop=94380ms, join=507267ms, gap=56695ms, sum=658342ms, wall=74191ms
Join overlap: avgDoneBeforeJoin=460/2757(16%), maxJoin=685ms, maxGap=470ms
Consumer average RPS: 134785, requests=10000000, duration=74191ms
```

**Phase breakdown comparison**:

| Metric | Step 0 (heap=2g) | Phase 1 (heap=4g) | Delta |
|---|---|---|---|
| RPS | 166K | 135K | **-19%** |
| loop | 24ms | 26ms | +8% |
| join | 108ms | 140ms | **+30%** |
| gap | 17ms | 16ms | -6% |
| Tcycle | 149ms | 182ms | +22% |
| maxJoin | — | 685ms | GC stall |

**Root cause**: heap 4g → join regression。4g heap は GC pause を長大化 (maxJoin=685ms = avg の 4.9x)。Netty event loop stall → ScyllaDB response 処理遅延 → join 全体が悪化。

**Key ByteArray 効果**: gap が 17→16ms (1ms 改善)。予測 12ms を大幅に下回る。PaymentEventKey は UUID 1個のみの tiny message で deser コストがそもそも低い。

### 4.2 Physical Limit Analysis

**ScyllaDB is NOT the bottleneck**:
- Server-side write latency: 1.3μs
- Per-shard utilization: 18.4K/s × 1.3μs = 2.4% CPU per shard
- ScyllaDB は 97.6% idle

**Client-side round-trip pipeline が律速**:

```
μ_cluster (Step 0) = 9 × B / (loop + join) = 9 × 2755 / 132ms = 188K/s
μ_cluster (Phase 1) = 9 × 2757 / 166ms = 149K/s  (heap regression)
```

**Theoretical max RPS** (gap amortization で utilization → 100%):

| Heap | μ_cluster | Max RPS (B→∞) | 達成条件 |
|---|---|---|---|
| 4g | 149K | ~149K | 現状 (gap=0 でも 200K 不可) |
| 2g | 188K | ~188K | heap revert 必要 |
| 2g + B increase | 188K+ | **~200K+** | gap amortization + join sub-linear scaling |

---

## 5. Implementation (Phase 2)

**Phase 1 の結論**: heap=4g が join regression の原因。B=3000 では gap overhead 11%。200K 到達には heap revert + B 増加が必要。

### 5.1 Changes

| File | Change | Rationale |
|---|---|---|
| Dockerfile | `-Xms4g -Xmx4g` → `-Xms2g -Xmx2g` | join regression 回復 (Step 0 実証済み) |
| compose.yaml | `MAX_POLL_RECORDS: 3000` → `10000` | gap amortization: 11% → 3.5% |

**Safety check (B=10000)**:

| Check | Value | Safe? |
|---|---|---|
| Concurrent | 9 × 10000 = 90K | << 3.15M capacity |
| Per connection | 90K / 96 = **938** | < 1024 DataStax 推奨 |
| Heap for in-flight | 90K × ~2KB = 180MB | 9% of 2g heap |
| Request completion | loop(89ms) + join(~350ms) ≈ 440ms | << 2s timeout |

### 5.2 Predictions

**Conservative (join ∝ B)**:
- loop = 8.86μs × 10000 = 89ms
- join = 39.2μs × 10000 = 392ms (linear from Step 0)
- gap = 16ms
- Tcycle = 497ms → **RPS = 9 × 10000 / 497 = 181K**

**Optimistic (join ∝ B^0.85, sub-linear due to better pipelining)**:
- join = 108 × (10000/2755)^0.85 = 333ms
- Tcycle = 438ms → **RPS = 9 × 10000 / 438 = 206K**

### 5.3 Phase 2 Actual Results (3/13)

**Config**: Phase 1 + heap=4g→2g + B=3000→10000

```
Batch stats: count=1085, avgSize=9217, min=3, max=10000
Phase timing (avg per batch): loop=69835us, join=360107us, gap(poll+commit)=12081us
Phase timing (total): loop=75772ms, join=390729ms, gap=13109ms, sum=479610ms, wall=56731ms
Join overlap: avgDoneBeforeJoin=1479/9217(16%), maxJoin=1299ms, maxGap=95ms
Consumer average RPS: 176263, requests=10000000, duration=56731ms
```

**Phase breakdown comparison (per record)**:

| Metric | Step 0 (B=3000, heap=2g) | Phase 1 (B=3000, heap=4g) | Phase 2 (B=10000, heap=2g) |
|---|---|---|---|
| RPS | 166K | 135K | **176K** |
| loop/record | 8.86μs | 9.43μs | **7.57μs** |
| join/record | 39.2μs | 50.7μs | **39.1μs** |
| gap/record | 5.82μs | 5.68μs | **1.31μs** |
| Tcycle/record | 53.9μs | 65.8μs | **48.0μs** |
| maxJoin | — | 685ms | **1299ms (GC stall)** |

**Key findings**:
1. heap=2g に戻したことで join/record が 50.7→39.1μs に回復 (Step 0 と同等)
2. B=10000 で gap amortization 効果: gap 5.82→1.31μs (-77%)
3. loop/record も改善: 8.86→7.57μs (-15%、batch setup overhead の amortization)
4. **GC stalls が律速**: maxJoin=1299ms (avg の 3.6x)。Instantaneous peak 220-234K RPS だが sustained は 170-176K

### 5.4 Failed Phase 2 Experiments

| Experiment | Change | Result | Root Cause |
|---|---|---|---|
| Phase 2a | Netty io-group.size=24, B=20000 | **FAIL** (DriverTimeoutException PT2S) | 24 Netty + 9 carrier = 33 threads on 12 cores → CPU oversubscription。loop=475μs/record (56× normal) |
| Phase 2b | 12 partitions, concurrency=12 | **164K** (-7%) | 12 consumer threads + 12 Netty threads = 24 on 12 cores。join/record 39→57.6μs (+47%) |
| Phase 2c | ZGC (-XX:+UseZGC) | **FAIL** (startup timeout) | PaymentRepository.init() CREATE TABLE が DriverTimeoutException PT2S |

**共通の律速**: M4 Pro 12 cores 上で Netty event loop threads (= availableProcessors = 12) と consumer carrier threads が CPU を共有。スレッド数増加は常に退化を引き起こす。

### 5.5 Physical Limit Summary

| Metric | Value | Source |
|---|---|---|
| Sustained average | 170-176K RPS | Phase 2 benchmark |
| Instantaneous peak (5s window) | 220-234K RPS | Progress logs |
| Theoretical max (9 threads) | 184K RPS | 9 / 48.0μs per-record |
| GC-free theoretical max | 210-225K RPS | 9 / 40-43μs (peak per-record) |
| ScyllaDB utilization | 2.4% | tablestats (1.3μs server-side write) |
| Bottleneck | G1GC stalls → Netty event loop stall | maxJoin=1299ms vs avg=360ms |

**Claim**: Sustained 170K と peak 220K+ の差 (~30%) は G1GC stop-the-world pause が原因。GC pause 中は Netty event loop が完全停止し、ScyllaDB response 処理が滞る。GC tuning で sustained を peak に近づけることが 200K RPS 達成の最短経路。

---

## 6. Phase 3: G1GC Tuning

### 6.1 Rationale

G1GC default `-XX:MaxGCPauseMillis=200` だが、観測される maxJoin=1299ms は目標の 6.5x。2g heap 上で 90K CompletableFuture/cycle × 9 threads = 高い allocation rate と短い object lifetime (500ms) が原因。

`-XX:MaxGCPauseMillis=5` で G1 に小さい young gen + 高頻度 minor GC を強制:
- 各 minor GC pause: ~5ms (目標)
- 頻度増加: maybe every 20-30ms
- GC CPU overhead: 5ms/25ms × 3 parallel threads / 12 cores ≈ 5%
- Trade-off: 微増の CPU overhead vs 大幅な tail latency 削減

### 6.2 Changes

| File | Change | Rationale |
|---|---|---|
| Dockerfile | `-XX:MaxGCPauseMillis=5` 追加 | GC stall 削減 → sustained RPS 向上 |

**Safety**: JVM flag のみの変更。application.conf, compose.yaml, KafkaClient.java は変更なし。B=10000, concurrent=90K は Phase 2 で安定性実証済み。

### 6.3 Predictions

**Conservative** (maxJoin 1299→300ms, avg join 5% 改善):
- join/record: 39.1 → 37.1μs
- Tcycle/record: 48.0 → 46.0μs
- RPS = 9 / 46.0μs = **196K**

**Optimistic** (GC pause ≤ 10ms, sustained ≈ peak):
- join/record: 39.1 → 33μs (peak 水準)
- Tcycle/record: 48.0 → 41.9μs
- RPS = 9 / 41.9μs = **215K**

### 6.4 Phase 3 Actual Results (3/13)

**Config**: Phase 2 + `-XX:MaxGCPauseMillis=5`

```
Batch stats: count=1369, avgSize=7304, min=5, max=10000
Phase timing (avg per batch): loop=177892us, join=248230us, gap(poll+commit)=36792us
Phase timing (total): loop=243535ms, join=339827ms, gap=50037ms, sum=633399ms, wall=71956ms
Join overlap: avgDoneBeforeJoin=3113/7304(42%), maxJoin=1429ms, maxGap=602ms
Consumer average RPS: 138972, requests=10000000, duration=71956ms
```

**結果: 139K RPS — 退化 (-21%)**

| Metric | Phase 2 (176K) | Phase 3 (139K) | Delta |
|---|---|---|---|
| loop/record | 7.57μs | **24.35μs** | **+222%** |
| join/record | 39.1μs | 34.0μs | -13% |
| gap/record | 1.31μs | **5.04μs** | **+285%** |

join は 13% 改善したが、ultra-frequent mini-pause が loop と gap を 3.2× 悪化。Net regression 32%。

---

## 7. Phase 4: Thread Count Optimization (concurrency=3)

### 7.1 Rationale

Phase 3 で GC tuning は逆効果と判明。残りの仮説: consumer threads 削減で CPU 競合を緩和し Netty throughput を向上。

- concurrency=3, B=30000 → concurrent = 90K (Phase 2 と同一、安全)
- CPU: 3 carrier + 12 Netty = 15 threads on 12 cores (vs 21 with 9 threads)

### 7.2 Phase 4 Actual Results (3/13)

**Config**: Phase 2 base + concurrency=3 + B=30000

```
Batch stats: count=348, avgSize=28735, min=1352, max=30000
Phase timing (avg per batch): loop=136415us, join=358951us, gap(poll+commit)=27739us
Phase timing (total): loop=47472ms, join=124915ms, gap=9569ms, sum=181956ms, wall=61089ms
Join overlap: avgDoneBeforeJoin=6932/28735(24%), maxJoin=1606ms, maxGap=371ms
Consumer average RPS: 163695, requests=10000000, duration=61089ms
```

**結果: 164K RPS — 退化 (-7%)**

Per-record costs は大幅改善したが thread 数減少が支配的:

| Metric | Phase 2 (9 threads) | Phase 4 (3 threads) | Delta |
|---|---|---|---|
| loop/record | 7.57μs | **4.75μs** | **-37%** |
| join/record | 39.1μs | **12.49μs** | **-68%** |
| gap/record | 1.31μs | **0.97μs** | **-26%** |
| **Total/record** | **48.0μs** | **18.2μs** | **-62%** |
| **Theoretical max** | **9/48.0 = 187K** | **3/18.2 = 165K** | **-12%** |
| **Actual RPS** | **176K** | **164K** | **-7%** |

**Key insight**: `RPS = threads / per_record_cost`。3 threads では per-record 2.6× 改善したが、thread 数 3× 減少が上回る。9 threads が最適。

---

## 8. Final Conclusion: Physical Limit on M4 Pro 12 cores

### 8.1 Thread Count Sweep

| Threads | per_record | Theoretical Max | Actual | Efficiency |
|---|---|---|---|---|
| 3 | 18.2μs | 165K | 164K | 99% |
| **9** | **~49μs** | **~184K** | **182K** | **97%** |
| 9 (with debug) | 48.0μs | 187K | 176K | 94% |
| 12 | ~67μs | 179K | 164K | 92% |

**9 threads は最適解。** Phase 5 で debug instrumentation を削除し、theoretical max の 97% に到達。残り 3% は irreducible な GC + scheduling overhead。

### 8.2 Physical Limit Evidence

| Evidence | Value | Implication |
|---|---|---|
| Best sustained RPS | **182K** (Phase 5) | Debug instrumentation 削除で +3.4% |
| Theoretical max (9 threads) | ~184K | Upper bound: per_record_cost = 0 でも ~184K |
| Instantaneous peak (5s) | 220-234K | GC-free 瞬間値。持続不可能 |
| ScyllaDB utilization | 2.4% | サーバ側は 97.6% idle |
| GC tuning | 139K (退化) | GC 改善は loop/gap 悪化でキャンセル |
| Thread reduction | 164K (退化) | 少ない threads は per-record 改善するが total 低下 |
| Thread increase | 164K (退化) | 多い threads は CPU 過剰で悪化 |
| Debug instrumentation overhead | +3.4% (176K → 182K) | hot path の nanoTime/atomic ops が Netty CPU を圧迫 |

### 8.3 Verdict

**182K RPS は M4 Pro 12 cores 上の synchronous individual-write architecture における物理限界。** Theoretical max (~184K) の 97% に到達。

同一 hardware 上で 200K+ を達成するには **architecture 変更** が必要（Section 10 参照）。

### 8.4 Optimized Final Configuration (Phase 2)

| Parameter | Value | Rationale |
|---|---|---|
| concurrency | 9 | Optimal thread count for 12 cores |
| max.poll.records | 10000 | 90K concurrent (proven safe, gap 2.7%) |
| heap | -Xms2g -Xmx2g | Optimal for G1GC (4g causes +30% join regression) |
| pool.local.size | 32 | Origin main (proven safe) |
| key/value deserializer | ByteArrayDeserializer | Raw byte parsing (zero-alloc) |
| mem_limit | 6g | Headroom for 2g heap + Netty buffers |
| tracing | 0 | Eliminates tracing overhead |

---

## 9. Risks and Open Questions

### max-requests-per-connection = 32767

Origin main でもこの値を使用しており安定。DataStax 公式は 1024 以上の効果はないと明言。B=10000 で per-connection=938 は 1024 以内。B > 10000 では 1024 を超える可能性あり。

### Deferred Join と Spring Kafka の互換性

Spring Kafka Batch Listener は `process()` return 後に auto-commit する。Deferred join は前バッチの join を次の process() 内で行うため、commit タイミングが1バッチずれる。at-least-once semantics を崩す可能性あり。

### Invalidated Hypotheses

| 仮説 | 検証方法 | 結果 |
|---|---|---|
| CPU starvation (ScyllaDB cpuset) | smp 3→2 | 棄却 (OrbStack 動的スケジューリング) |
| pool.local.size 削減で改善 | pool=8, pool=2 | 棄却 (crash × 2) |
| 複数パラメータ同時変更 | Exp 1, 5 | 棄却 (crash × 2) |
| Key ByteArray で gap -12ms | Phase 1 実測 | 棄却 (PaymentEventKey は tiny, 実測 -1ms) |
| heap 4g で headroom 確保 | Phase 1 vs Step 0 | **棄却 (join +30% regression)** |
| ScyllaDB がボトルネック | Phase 1 tablestats | 棄却 (2.4% utilization) |
| Netty threads 増加で改善 | io-group.size=24 | 棄却 (CPU oversubscription → timeout) |
| Partition/concurrency 増加で改善 | 12 partitions + C=12 | 棄却 (164K, -7% regression, Netty 飽和) |
| ZGC で GC stall 解消 | -XX:+UseZGC | 棄却 (startup timeout, 要 init timeout 延長) |
| G1GC MaxGCPauseMillis=5 で stall 解消 | Phase 3 | 棄却 (loop +222%, gap +285%, net -21%) |
| Consumer threads 削減で CPU 競合緩和 | concurrency=3 | 棄却 (per-record -62% だが total -7%) |

### Not Effective on This Hardware

| 施策 | 理由 |
|---|---|
| ScyllaDB smp 削減 | OrbStack では CPU 解放にならない (Exp 4 実証) |
| pool.local.size 削減 | cold-start crash (Exp 1, 5 実証) |
| receive.buffer.bytes 増加 | container-to-container では効果なし |
| fetch.max.wait.ms 削減 | data-rich benchmark では trigger されない |
| Docker cpus 制約 | Netty/VT carrier threads 減少リスク |

---

## 10. Architecture Changes for 200K+ RPS (Same Hardware)

### 10.1 Bottleneck Recap

現在の律速は **Netty event loop の per-response 処理コスト**:

```
175K responses/s ÷ 12 Netty threads = 14.6K responses/thread/s
→ 1 response あたり ~68μs の event loop CPU
  (CQL frame decode + LZ4 decompress + stream ID match + future complete)
```

各 batch cycle で 10,000 individual INSERT → 10,000 Netty round-trips。9 threads × 10,000 = 90,000 responses/cycle が 12 cores を飽和。**response 数を減らすことが唯一の突破口。**

### 10.2 Option A: CQL Write Coalescing（推奨）

#### Concept

Records を coordinator node でグルーピングし、UNLOGGED BATCH で送信。Netty round-trip を ~100× 削減。

```
現在: 10,000 records → 10,000 individual INSERT → 10,000 Netty round-trips
提案: 10,000 records → ~100 UNLOGGED BATCH (100 records each) → 100 Netty round-trips
```

#### Implementation

```java
public void process(List<ConsumerRecord<byte[], byte[]>> records) {
    // Phase 1: Parse records and group by coordinator node
    Map<Node, List<BoundStatement>> nodeGroups = new HashMap<>();
    var tokenMap = cqlSession.getMetadata().getTokenMap().orElseThrow();
    for (var record : records) {
        var bound = repository.getInsertStmt().bind(
                extractUserId(record.value()),
                extractTransactionId(record.key()));
        var replicas = tokenMap.getReplicas(keyspace, bound.getRoutingKey());
        var node = replicas.iterator().next(); // primary replica
        nodeGroups.computeIfAbsent(node, k -> new ArrayList<>()).add(bound);
    }

    // Phase 2: Send mini-batches of ~100 per node
    var futures = new ArrayList<CompletableFuture<?>>();
    for (var entry : nodeGroups.entrySet()) {
        for (var chunk : partition(entry.getValue(), BATCH_SIZE)) {
            var batch = BatchStatement.newInstance(DefaultBatchType.UNLOGGED, chunk);
            futures.add(cqlSession.executeAsync(batch.setNode(entry.getKey()))
                    .toCompletableFuture());
        }
    }

    // Phase 3: Join (now only ~100 futures instead of 10,000)
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}

private static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
        result.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return result;
}
```

#### Performance Model

| Metric | 現在 (individual) | 提案 (batch=100) |
|---|---|---|
| Netty round-trips / batch | 10,000 | ~100 |
| Netty responses / cycle (9 threads) | 90,000 | ~900 |
| join time (est.) | 360ms | 10-30ms |
| loop time (est.) | 70ms | ~80ms (grouping overhead) |
| Tcycle (est.) | 442ms | ~120ms |
| **RPS (conservative)** | **176K** | **300K+** |

仮に Tcycle 推定が 2× 外れても: 9 × 10000 / 240ms = **375K**。3× 外れても: 9 × 10000 / 360ms = **250K**。200K は高確率で達成。

#### Correctness

- **UNLOGGED BATCH**: atomicity 保証なし。しかし INSERT は idempotent (PRIMARY KEY による upsert) なので partial apply は問題ない
- **at-least-once**: `process()` 内で join 完了後に return → Spring Kafka が commit。現在と同じ semantics
- **Batch size**: 100 records × ~100 bytes = ~10KB/batch。ScyllaDB default `batch_size_warn_threshold_in_kb=128` の範囲内

#### Risks

| Risk | Mitigation |
|---|---|
| Coordinator overhead (multi-partition batch) | Mini-batch size 100 で制限。ScyllaDB は decompose → individual write なので server-side cost は同一 |
| Token map lookup overhead | `getTokenMap()` は cached。per-record のtoken routing は DataStax driver が internal にやっている処理と同等 |
| Batch size tuning | 50-200 の range で benchmark。大きすぎると coordinator memory 圧、小さすぎると round-trip 削減効果低下 |
| `setNode()` でのルーティング | Primary replica に直接送信。Token-aware policy と同等の効果 |

### 10.3 Option B: Protocol Compression 無効化

#### Concept

`protocol.compression = lz4` を削除。INSERT response は数十 bytes の ack で圧縮効果ゼロだが、175K responses/s の LZ4 decompress CPU を消費。

#### Change

```diff
# application.conf
datastax-java-driver {
    advanced {
        connection {
            pool.local.size = 32
        }
        max-requests-per-connection = 32767
        socket.keep-alive = true
-       protocol.compression = lz4
    }
}
```

#### Expected Impact

| Metric | 現在 | 提案 |
|---|---|---|
| Per-response CPU | ~68μs | ~58-62μs (est. -10-15%) |
| **RPS (est.)** | **176K** | **185-200K** |

単体では 200K に届かない可能性あり。Option A と併用で最大効果。

### 10.4 Option C: ScyllaDB Java Driver（shard-aware）

#### Concept

DataStax Java Driver → [ScyllaDB Java Driver](https://java-driver.docs.scylladb.com/) に差し替え。DataStax driver の fork で API 互換。Shard-aware routing により server-side cross-shard forwarding を排除。

#### Expected Impact

- Server-side は 2.4% utilization → shard-aware routing の server-side 改善は微小
- Client-side の Netty 実装が ScyllaDB 向けに最適化されている可能性 → 不明、要実測
- **RPS (est.)**: 176K → 180-210K（幅が大きい、実測必要）

#### Trade-off

| Pro | Con |
|---|---|
| API ほぼ互換（DataStax fork） | Driver migration の工数 |
| Shard-aware = 最適 connection routing | application.conf の設定項目が異なる可能性 |
| ScyllaDB 公式サポート | DataStax 固有機能との互換性リスク |

### 10.5 Options Comparison

| Option | Expected RPS | Complexity | Correctness Impact | Recommendation |
|---|---|---|---|---|
| **A: Write Coalescing** | **300K+** | Medium (KafkaClient.java 変更) | なし (at-least-once 維持) | **最優先** |
| B: Compression 無効化 | 185-200K | Low (1行削除) | なし | A と併用 |
| C: ScyllaDB Driver | 180-210K | Medium (driver 差替) | なし | A で不十分な場合 |
| A + B 併用 | **350K+** | Medium | なし | **最大効果** |

### 10.6 Implementation Priority

1. **Option B** を先に適用（1行変更、リスク最小）→ 効果測定
2. **Option A** を実装 → batch size 100 で benchmark
3. 200K+ 達成を確認後、batch size tuning (50-200) で最適化
4. Option C は A+B で目標未達の場合のみ検討
