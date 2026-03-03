## 3. パフォーマンスと並行処理

### 文字列生成の最適化
`PaymentEvent.java` 内の `getInsertInto()` や `getCreateTable()` は、呼び出しごとに `StringBuilder` や `String.format` を使用してSQLを生成しています。
- **改善案:** これらは定数（`static final`）として定義するか、一度だけ生成してキャッシュするように変更すべきです。高負荷時にはGCのオーバーヘッドになります。

## 4. 具体的なリファクタリング候補

### `fraud-detection-service`

| ファイル | 改善点 |
| :--- | :--- |
| `PaymentEvent.java` | SQL生成ロジックをRepositoryへ移動。SQL文字列の定数化。 |
| `KafkaClient.java` | `Executors.newSingleThreadExecutor()` の使用箇所（トピック監視）を `ScheduledExecutorService` または Springの `SmartLifecycle` に変更。無限ループスレッドの放置リスクを回避。 |
| `DataType.java` | `toString()` のオーバーライドではなく、`getValue()` メソッドなどで明示的に値を取得するように変更（好みの問題ですが、意図を明確にするため）。 |

### `payment-service`

| ファイル | 改善点 |
| :--- | :--- |
| `KafkaTopicCreator.java` | `waitForBrokers` メソッドで `Thread.sleep` を使用したループ待機を行っています。`Awaitility` ライブラリを使用するか、より堅牢なリトライロジック（Spring Retryなど）を導入すべきです。 |
| `PaymentEventsProduceUseCase.java` | 大量のイベント生成 (`n` 回ループ) が同期的です。負荷テスト用途であれば、ここも並行化（Virtual Threads）することで、より高い負荷をかけることができます。 |

## 5. ライブラリ・依存関係

- **Lombok:** 推奨（ボイラープレート削減）
- **MapStruct:** 推奨（Protoオブジェクトとドメインオブジェクトの変換用）
- **Awaitility:** 推奨（テストや初期化待ちのポーリング処理用）

## 6. まとめ

コードベースはシンプルで理解しやすいですが、プロダクションレベルの負荷や保守性を考慮すると、以下の3点が優先度の高い改善項目です：

1.  **Virtual Threads の導入:** I/O待ち（Kafka, ScyllaDB）の効率化。
2.  **ボイラープレートの削減:** Java Records や Lombok の活用。
3.  **責務の分離:** ドメインオブジェクトと永続化ロジックの分離。

これらの変更を適用することで、コードの記述量が減り、パフォーマンスとメンテナンス性が向上します。
