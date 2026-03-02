# ScyllaDB 書き込みパフォーマンス最適化ガイド

## 3. 今後の推奨アクション (Next Steps)

2.  **Compaction Strategy の見直し**: 
    *   `payment-events` テーブルが「追記中心で、更新・削除がほとんどない」場合、`TimeWindowCompactionStrategy (TWCS)` が最適です。
    *   ScyllaDB サーバー側の `scylla.yaml` またはテーブル定義 (`ALTER TABLE`) で変更可能です。
