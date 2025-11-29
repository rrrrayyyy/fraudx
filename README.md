# fraudx

# Detection rules
1. velocity/frequency (same card/device_id used M times in N min)
    - => Sliding window
        - Redis SortedSets
        - Redis HyperLogLog
1. Transactional pattern deviation (amount deviates 3σ from user mean)
    - => ScyllaDB historical records, Stream aggregation
1. account/user linkage
    - same IP address or device_id between multiple accounts/users
    - same card used by multiple users
    - => Graph traversal
1. unusual geo location (transaction interval vs time required to move)
    - Redis Geohash

# Performance optimization
- producer/consumer
    - 4: (189208, 201249)
    - 4: (410120, 411174) with 50M (m3 pro)

# procedures
```zsh
./gradlew generateProto

./gradlew :payment-service:bootRun \
-DcomposeUpD=true \
-Dkafka.connect=true \
--args="--logging.level.com.example.payment_service=INFO \
        --spring.kafka.producer.compression-type=lz4 \
        --spring.kafka.producer.buffer-memory=134217728 \
        --kafka.topics.payment.replication-factor=3 \
        --kafka.topics.payment.partitions=4 \
        --spring.kafka.producer.batch-size=1048576 \
        --spring.kafka.producer.linger-ms=50 \
        --spring.kafka.producer.acks=1"

./gradlew :fraud-detection-service:bootRun \
--args="--logging.level.com.example.fraud_detection_service=INFO \
        --spring.kafka.consumer.fetch-min-size=262144 \
        --spring.kafka.consumer.max-partition-fetch-bytes=10485760 \
        --spring.kafka.consumer.enable-auto-commit=false \
        --spring.kafka.consumer.max-poll-records=50000 \
        --spring.kafka.consumer.concurrency=4"

curl -X POST "http://localhost:8080/payment-events?n=10000000"
```

# development environment setup
- Spring Initializr: Create a Gradle Project
- `mv fraudx/{*,.*} .`
- project-root > build.gradle に subprojects を追加
    - subprojects はsub moduleに共通適用される設定
    - version はapp versionで、SNAPSHOT == 開発中の最新verを意味する（Release Candidate前）
```groovy
subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    group = 'com.example'
    version = '0.0.1-SNAPSHOT'
    sourceCompatibility = '25'

    repositories {
        mavenCentral()
    }
}
```
- project-root > settings.gradle に追加
    -  `include 'payment-service', 'fraud-detection-service'`
    - これらの名前で project-root にdirectoryが作られ、そこにmoduleがinitializeされている必要がある
- Spring Initializr: Create a Gradle Project x2
    - sub module で settings.gradle を消す
        - rootProject 設定がdefaultで入っているが、sub module で指定不要のため
- payment-service > build.gradle > dependencies に以下を追加
```groovy
    implementation 'org.springframework.boot:spring-boot-starter-web'
```
- Developer: Reload Window
    - sub module を spring boot dashboard に反映させるため




# Spring Boot memo
- build.gradle を変更したら Java: Clean Java Language Server Workspace も必要（classpath 再認識）
- CommandLineRunner と ApplicationRunner の違い
    - 後者は起動時の引数を型定義できる（前者は `--key=value` をparse）
- ./gradlew --stop # to stop deamon

# Kafka memo
- broker: message retention + distribution + replication
    - replication.factor=3
- controller: cluster manager
    - KRaft mode では controller 専用ノードを立てるのが推奨
- Apache Kafka は「分散メッセージキュー／イベントログシステム」
    - Kafka Streams は、Kafkaに流れるデータをリアルタイムで加工・集約・結合するためのJavaライブラリ
- consumer 起動時の CoordinatorNotAvailableException
    - 結論、KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR 未指定（default: 3) > num of brokers (2) で発生していた...
        - 1を指定して治った
    - 結果として broker 設定は以下で良かった
        - KAFKA_LISTENERS: PLAINTEXT://:19092,PLAINTEXT_HOST://:9092
        - KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker-1:19092,PLAINTEXT_HOST://localhost:9004

# TODO
- 動的にmessage内容を変える + subscriber側にそのカウントを伝える(精度計算のため)
- Redis and/or Scylla と接続する
- Kafka Stream


