# fraudx-base

# procedures
```zsh
# ./gradlew :fraud-detection-service:bootRun

./gradlew :payment-service:bootRun
# move to another terminal
curl -X POST "http://localhost:8080/publish-payment-event?n=10"
```

# development environment setup
- Spring Initializr: Create a Gradle Project
- `mv fraudx-base/{*,.*} .`
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
- Apache Kafka は「分散メッセージキュー／イベントログシステム」
    - Kafka Streams は、Kafkaに流れるデータをリアルタイムで加工・集約・結合するためのJavaライブラリ