# ScyllaDB 書き込みパフォーマンス最適化ガイド

## 3. 今後の推奨アクション (Next Steps)

さらなるパフォーマンス向上には、以下の対応を検討してください。

1.  **ScyllaDB Java Driver への移行**: 
    *   DataStax Driver の代わりに [ScyllaDB Java Driver](https://github.com/scylladb/java-driver) を使用することを強く推奨します。
    *   Scylla Driver は **Shard-Awareness** をネイティブサポートしており、クライアント側で適切なシャード（CPUコア）を選択してリクエストを送ることができます。これにより、レイテンシとCPU負荷が大幅に改善します。

2.  **Compaction Strategy の見直し**: 
    *   `payment-events` テーブルが「追記中心で、更新・削除がほとんどない」場合、`TimeWindowCompactionStrategy (TWCS)` が最適です。
    *   ScyllaDB サーバー側の `scylla.yaml` またはテーブル定義 (`ALTER TABLE`) で変更可能です。
