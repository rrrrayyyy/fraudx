# ScyllaDB 書き込みパフォーマンス最適化ガイド

## 1. 現状の課題と分析

現在、`fraud-detection-service` において、Publisher の RPS が 0.7M であるのに対し、Subscriber (ScyllaDB への書き込み) の RPS が 0.1M と大幅に低い状態です。

### ボトルネックの特定
調査の結果、以下の複合的な要因がパフォーマンス低下の原因と考えられます。

1.  **過大な `max-poll-records` とリバランスのリスク (最大要因)**:
    *   `compose.yaml` 上で `max-poll-records` が `50000` に設定されています。
    *   処理時間が `max.poll.interval.ms` を超えると、Consumer Group のリバランスが発生し、処理が一時停止します。50,000件の処理は容易にこのタイムアウトを超過するため、これがスループット低下の主因である可能性が極めて高いです。

2.  **非同期書き込みの制御 ("Fire-and-forget" の弊害)**:
    *   現在の実装は `cqlSession.executeAsync` の完了を待たずにループを回しています。
    *   セマフォ (`32768`) で制限していますが、リクエストがバースト的に発生し、ドライバ内部のキューやネットワーク帯域を圧迫している可能性があります。
    *   また、非同期タスクの完了を待たずにメソッドを抜けるため、Kafka のオフセットコミットと実際の書き込み完了タイミングがずれており、**データロスト**のリスクがあります。

3.  **ドライバと接続プールの不整合**:
    *   現在使用している `spring-boot-starter-data-cassandra` は標準の DataStax Java Driver です。これはデフォルトでは ScyllaDB の **Shard-Awareness** (CPUコアごとのシャードへの最適化) を完全にはサポートしていません。
    *   ScyllaDB は各 CPU コアが独立したシャードとして動作するため、ドライバが特定のシャードを狙ってリクエストを送れない場合、ノード内部での転送オーバーヘッドが発生します。
    *   また、現在の接続数 (`pool.local.size = 12`) は、全シャード (3ノード × 3シャード = 9) に均等に接続が行き渡るには不十分な可能性があります。

---

## 2. 実施済みの改善アクション

以下の変更をコードベースに適用しました。

### 2.1 Kafka Consumer 設定の最適化 (`compose.yaml`)

リバランスを回避し、安定した並列処理を実現するため、設定を変更しました。

*   **`SPRING_KAFKA_CONSUMER_CONCURRENCY: 9`**: パーティション数に合わせ、9つのスレッドが並列にメッセージを消費します。
*   **`SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS: 5000`**: 1回のpollで取得する件数を 50,000 から 5,000 に減らし、poll頻度を上げることでリバランスを回避します。

### 2.2 ScyllaDB 接続プールの調整 (`application.conf`)

Non-Shard-Aware ドライバの制約を緩和するため、接続数を増やしました。

```hocon
datastax-java-driver {
    advanced {
        connection {
            # 全シャードに接続が行き渡る確率を高めるため、
            # pool.local.size を 12 から 24 に増強
            pool.local.size = 24 
        }
    }
}
```

### 2.3 コード改修: バッチ同期書き込みと冪等性の確保 (`KafkaClient.java`)

`KafkaClient.java` をリファクタリングし、安全性と信頼性を向上させました。

1.  **Batch Async Wait パターンの導入**:
    *   `inFlight` Semaphore を廃止しました。
    *   代わりに `CompletableFuture.allOf(...).join()` を使用し、バッチ内の全書き込みが完了するまで待機するようにしました。これにより、バックプレッシャーが自然にかかり、Kafka のオフセットコミットの安全性も向上します。
2.  **冪等性の明示**:
    *   PreparedStatement に `setIdempotent(true)` を設定しました。これにより、タイムアウト時などにドライバが安全にリトライを行えるようになります。

```java
// 修正後のロジック概要
List<CompletableFuture<Void>> futures = new ArrayList<>(records.size());

for (var record : records) {
    // 冪等性を明示してバインド
    var bound = insertStmt.bind(...).setIdempotent(true);
    
    // 非同期実行し、例外ハンドリングを追加
    futures.add(cqlSession.executeAsync(bound).toCompletableFuture().exceptionally(ex -> {
        log.error("Write failed...", ex);
        return null;
    }));
}

// 全タスクの完了を待つ (バックプレッシャー & 安全なコミット)
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

---

## 3. 今後の推奨アクション (Next Steps)

さらなるパフォーマンス向上には、以下の対応を検討してください。

1.  **ScyllaDB Java Driver への移行**: 
    *   DataStax Driver の代わりに [ScyllaDB Java Driver](https://github.com/scylladb/java-driver) を使用することを強く推奨します。
    *   Scylla Driver は **Shard-Awareness** をネイティブサポートしており、クライアント側で適切なシャード（CPUコア）を選択してリクエストを送ることができます。これにより、レイテンシとCPU負荷が大幅に改善します。

2.  **Compaction Strategy の見直し**: 
    *   `payment-events` テーブルが「追記中心で、更新・削除がほとんどない」場合、`TimeWindowCompactionStrategy (TWCS)` が最適です。
    *   ScyllaDB サーバー側の `scylla.yaml` またはテーブル定義 (`ALTER TABLE`) で変更可能です。
