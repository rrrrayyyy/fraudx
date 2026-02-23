# Spring Boot Codebase Review: Configuration & Best Practices

このドキュメントは、現在のコードベースにおける Spring Boot の設定および実装パターンに関するレビュー結果をまとめたものです。
特に、以下の2点に焦点を当てています。

1.  **デフォルト値で動作するため明示的な指定が不要な設定 (Redundant Configuration)**
2.  **より自然な Spring Boot の実装パターンが存在する箇所 (Workarounds & Anti-patterns)**

---

## 1. 冗長な設定 (Redundant Configuration)

以下の設定は Spring Boot や依存ライブラリのデフォルト値と同じであるため、削除しても動作に影響しません。設定ファイルを簡潔に保つために削除を推奨します。

### `fraud-detection-service/src/main/resources/application.yaml`

| プロパティ | 現在の値 | デフォルト値 | 説明 |
| :--- | :--- | :--- | :--- |
| `spring.kafka.consumer.fetch-min-size` | `1` | `1` | サーバーからフェッチする最小バイト数。デフォルトで1バイトです。 |
| `spring.kafka.consumer.enable-auto-commit` | `true` | `true` | コンシューマのオフセットを自動コミットするかどうか。デフォルトはtrueです。 |
| `spring.kafka.consumer.max-poll-records` | `500` | `500` | 1回のpoll()呼び出しで返される最大レコード数。 |

### `payment-service/src/main/resources/application.yaml`

| プロパティ | 現在の値 | デフォルト値 | 説明 |
| :--- | :--- | :--- | :--- |
| `spring.kafka.producer.compression-type` | `none` | `none` | プロデューサの圧縮タイプ。デフォルトは圧縮なしです。 |
| `spring.kafka.producer.buffer-memory` | `33554432` | `33554432` | プロデューサがバッファリングに使用できるメモリの合計バイト数 (32MB)。 |
| `spring.kafka.producer.batch-size` | `16384` | `16384` | 同じパーティションへのレコードをバッチ処理する際の最大サイズ (16KB)。 |
| `spring.kafka.producer.acks` | `all` | `all` | リーダーが受信確認を待つレプリカの数。Kafka 3.0以降、デフォルトは `all` (-1) です。 |

---

## 2. 実装の改善点 (Workarounds & Anti-patterns)

現在の実装には、Spring Boot の標準機能を使わずに手動で実装されている「ワークアラウンド」的な箇所が見受けられます。これらを Spring Boot の標準的な方法に置き換えることで、コード量を削減し、保守性を向上させることができます。

### 2.1 Kafka 設定の手動 Bean 定義 (Manual Bean Definition)

**対象ファイル:**
- [KafkaConsumerConfig.java](file:///Users/ray/code/g4/fraudx/fraud-detection-service/src/main/java/com/example/fraud_detection_service/adapter/KafkaConsumerConfig.java)
- [KafkaConfig.java](file:///Users/ray/code/g4/fraudx/payment-service/src/main/java/com/example/payment_service/adapter/KafkaConfig.java)

**現状:**
`@Value` アノテーションを使ってプロパティ値を一つずつ読み込み、`HashMap` に詰めて `DefaultKafkaConsumerFactory` や `DefaultKafkaProducerFactory` を手動で生成しています。

**推奨される修正:**
Spring Boot の `KafkaAutoConfiguration` (spring-kafka) は、`application.yaml` に記述された `spring.kafka.*` プロパティを自動的に読み込み、適切な `ConsumerFactory` や `ProducerFactory` を構成します。
手動での Bean 定義を削除し、必要であれば `application.yaml` の設定のみで完結させるべきです。

**Before:**
```java
@Value("${spring.kafka.bootstrap-servers}")
private String bootstrapServers;

@Bean
public ConsumerFactory<byte[], byte[]> consumerFactory() {
    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    // ... 手動でプロパティを設定
    return new DefaultKafkaConsumerFactory<>(props);
}
```

**After:**
(コード削除のみ。`application.yaml` に設定があれば自動構成されます)

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
