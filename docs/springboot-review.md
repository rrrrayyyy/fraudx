# Spring Boot Codebase Review: Configuration & Best Practices

このドキュメントは、現在のコードベースにおける Spring Boot の設定および実装パターンに関するレビュー結果をまとめたものです。
特に、以下の2点に焦点を当てています。

2.  **より自然な Spring Boot の実装パターンが存在する箇所 (Workarounds & Anti-patterns)**

---

## 2. 実装の改善点 (Workarounds & Anti-patterns)

現在の実装には、Spring Boot の標準機能を使わずに手動で実装されている「ワークアラウンド」的な箇所が見受けられます。これらを Spring Boot の標準的な方法に置き換えることで、コード量を削減し、保守性を向上させることができます。

### 2.2 Kafka メッセージの手動シリアライズ/デシリアライズ

**対象ファイル:**
- [KafkaClient.java (Fraud Detection)](file:///Users/ray/code/g4/fraudx/fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/KafkaClient.java)
- [KafkaClient.java (Payment)](file:///Users/ray/code/g4/fraudx/payment-service/src/main/java/com/example/payment_service/adapter/KafkaClient.java)

**現状:**
Kafka のメッセージを `byte[]` (バイト配列) として送受信し、アプリケーションコード内で手動で `KafkaProtobufDeserializer` や `KafkaProtobufSerializer` を `new` して変換しています。

**検証済みの修正手順:**

1.  **KafkaProtobufDeserializer の改修:**
    Spring Boot のプロパティから型情報を読み込めるように、`KafkaProtobufDeserializer` に `configure` メソッドを実装します。

    ```java
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        String typeProp = isKey ? "protobuf.key.type" : "protobuf.value.type";
        Object typeName = configs.get(typeProp);
        if (typeName == null) typeName = configs.get("spring.kafka.properties." + typeProp);
        // ... (ReflectionでParserを取得)
    }
    ```

2.  **application.yaml の更新:**
    `key-deserializer`, `value-deserializer` を指定し、`properties` セクションで具体的な型を指定します。

    ```yaml
    spring:
      kafka:
        consumer:
          key-deserializer: com.example.fraud_detection_service.adapter.KafkaProtobufDeserializer
          value-deserializer: com.example.fraud_detection_service.adapter.KafkaProtobufDeserializer
          properties:
            protobuf.key.type: com.example.proto.Event$PaymentEventKey
            protobuf.value.type: com.example.proto.Event$PaymentEventValue
    ```

3.  **KafkaClient の修正:**
    `@KafkaListener` や `KafkaTemplate` のジェネリクス型を `byte[]` からドメインオブジェクトに変更します。

    **Consumer:**
    ```java
    @KafkaListener(topics = "${kafka.topics.payment.name}", batch = "true")
    public void process(List<ConsumerRecord<PaymentEventKey, PaymentEventValue>> records) {
        // ...
    }
    ```

    **Producer:**
    ```java
    private final KafkaTemplate<PaymentEventKey, PaymentEventValue> protoTemplate;
    // ...
    protoTemplate.send(topic, key, value);
    ```

**検証結果:**
上記の手順に従ってコードを変更し、`./gradlew build` が正常に成功することを確認しました。

### 2.3 トピック存在確認のポーリング (Polling for Topic Existence)

**対象ファイル:**
- [KafkaClient.java (Fraud Detection)](file:///Users/ray/code/g4/fraudx/fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/KafkaClient.java#L64)

**現状:**
`startIfTopicExists` メソッド内で `AdminClient` を使い、無限ループでトピックの存在をポーリングしています。トピックが見つかって初めてリスナーコンテナを手動で `start()` させています。

**推奨される修正:**
これは不要な複雑さを招くワークアラウンドです。
1.  **自動作成:** `NewTopic` Bean を定義することで、起動時に Spring Boot が自動的にトピックを作成してくれます。
2.  **自動再接続:** トピックがまだ存在しない場合でも、Spring Kafka のリスナーコンテナは接続できるまでバックグラウンドでリトライし続けます。アプリ側で起動待ちをする必要はありません。

**改善案:**
```java
@Bean
public NewTopic paymentTopic() {
    return TopicBuilder.name("payment-events")
            .partitions(4)
            .replicas(3)
            .build();
}
```

### 2.4 Semaphore による手動スロットリング

**対象ファイル:**
- [KafkaClient.java (Fraud Detection)](file:///Users/ray/code/g4/fraudx/fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/KafkaClient.java#L36)

**現状:**
`Semaphore` を使って同時実行数を制限しようとしていますが、Kafka Consumer は基本的にシングルスレッド（パーティション単位）で動作するため、コンシューマ内でのブロッキング処理に対する並行数制御としては不完全または不適切です。

**推奨される修正:**
Spring Kafka の `ConcurrentKafkaListenerContainerFactory` の `concurrency` 設定（`spring.kafka.listener.concurrency`）を使用してください。これにより、指定した数のコンシューマスレッドが起動し、パーティションを分散して処理することで、安全かつ効率的に並行処理を行えます。

### 2.5 Cassandra Session の手動生成

**対象ファイル:**
- [ScyllaConfiguration.java](file:///Users/ray/code/g4/fraudx/fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/ScyllaConfiguration.java)

**現状:**
`CqlSession` を手動でビルドしています。

**推奨される修正:**
`spring-boot-starter-data-cassandra` を使用している場合、`spring.cassandra.*` プロパティを設定するだけで `CqlSession` は自動構成されます。特別なドライバ設定が必要な場合も、`AbstractCassandraConfiguration` を継承するか、`DriverConfigLoaderBuilderCustomizer` Bean を定義する方が Spring Boot の作法に沿っています。
