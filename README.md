# fraudx

# procedures
```zsh
./gradlew generateProto

./gradlew :payment-service:bootRun -DcomposeUpD=true -Dkafka.connect=true --args="--kafka.topic-config.payment.partitions=3 --kafka.topic-config.payment.replication-factor=1"

./gradlew :fraud-detection-service:bootRun --args='--n=10000'

# move to another terminal
curl -X POST "http://localhost:8080/payment-events?n=10000"
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
- [x] Kafka docker compose up -d の成功確認
- [x] SpringBoot 起動時引数でKafka docker compose up -d + Kafkaへの接続を行う（引数なしで両方行わない）
- [x] Kafka producer client実装
- [ ] Kafka subscriber 実装 (Reactive)
- [ ] kafka-producer-perf-test.sh と kafka-consumer-perf-test.sh を使い、メッセージサイズ・batching・compression・acks を変えて実測するのが必須

