# Codebase Audit Report

## 1. 概要
本レポートは `fraudx` リポジトリ全体の監査結果をまとめたものです。インフラストラクチャ、ビルドプロセス、可観測性（Observability）、およびドキュメンテーションの観点から、現状の課題と改善案を提示します。

Javaコードの詳細な改善案については、別紙 [Java Improvements](./java_improvements.md) を参照してください。

## 2. インフラストラクチャとDocker (Infrastructure & Docker)

### 2.1. Dockerfileの最適化
現在の `Dockerfile` はシンプルですが、ビルドの再現性とセキュリティ、キャッシュ効率の面で改善の余地があります。

*   **現状:** `COPY build/libs/*.jar app.jar` を使用しており、ホストマシンでのビルド（`./gradlew bootJar`）を前提としています。
*   **課題:**
    *   ビルド環境（JDKバージョンなど）が開発者ごとに異なる可能性があります。
    *   Dockerキャッシュが効きにくく、わずかなコード変更でも全JARのコピーが発生します。
    *   `root` ユーザーでアプリケーションが実行されています。
*   **改善案:**
    1.  **Multi-stage Buildの導入:** Docker内でビルドを完結させ、環境の一貫性を保証します。
    2.  **Layered JARの活用:** Spring Bootのレイヤー機能を使用し、依存ライブラリ（変更頻度が低い）とアプリケーションコード（変更頻度が高い）を別レイヤーとしてコピーすることで、ビルド時間を短縮します。
    3.  **Non-root User:** セキュリティ向上のため、専用のユーザーを作成して実行します。

**改善後のDockerfile例:**
```dockerfile
# Stage 1: Build
FROM eclipse-temurin:25-jdk-ubi10-minimal AS builder
WORKDIR /build
COPY . .
RUN ./gradlew bootJar --no-daemon
RUN java -Djarmode=layertools -jar build/libs/*.jar extract

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-ubi10-minimal
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

### 2.2. Docker Compose構成の整理
*   **現状:** `compose.yaml` と `metrics-compose.yaml` で Kafka クラスタの定義が重複しており、保守性が低下しています。また、ネットワーク名が `fraudx-net` と `kafka-net` で不統一です。
*   **改善案:**
    *   **Compose Include:** Docker Composeの `include` 機能を使用するか、`extends` を使用して共通定義を切り出します。
    *   **ネットワークの統一:** 全てのサービスを同一のネットワーク（例: `fraudx-network`）に配置し、通信を簡素化します。

## 3. ビルドプロセスとCI/CD (Build Process)

### 3.1. タスクランナーの導入
*   **現状:** `README.md` に長いコマンドライン（`./gradlew clean bootJar && docker compose ...`）が記載されています。
*   **改善案:** `Makefile` または `Taskfile` (Task) を導入し、よく使うコマンドをエイリアス化することで、開発体験（DX）を向上させます。

**Makefile例:**
```makefile
.PHONY: up down logs

up:
	./gradlew bootJar
	docker compose up -d --build

down:
	docker compose down -v

logs:
	docker compose logs -f
```

### 3.2. Protoファイルの管理
*   **現状:** `.proto` ファイルがリポジトリ内にありますが、Lintingや破壊的変更（Breaking Change）のチェックが行われていません。
*   **改善案:** **Buf** (https://buf.build/) を導入し、CIパイプラインでProtoファイルの品質を管理することを推奨します。

## 4. 可観測性 (Observability)

### 4.1. Prometheus Service Discovery
*   **現状:** `prometheus.yml` に `controller-1`, `broker-1` などのホスト名が静的に記述されています。
*   **改善案:** Dockerのスケーリングに対応するため、Prometheusの `dns_sd_configs`（DNS Service Discovery）を使用するか、Docker Composeのサービス名解決に依存しない動的な検出設定を検討します。

### 4.2. Grafana Provisioning
*   **現状:** Grafanaの設定が含まれていません（`metrics-compose.yaml` にも見当たりません）。
*   **改善案:** Grafanaコンテナを追加し、ダッシュボードとデータソースをコード管理（Provisioning）することで、可視化環境を即座に立ち上げられるようにします。

## 5. ドキュメンテーション (Documentation)

### 5.1. アーキテクチャ図
*   **現状:** テキストベースの説明のみです。
*   **改善案:** Mermaid.js などを使用して、システム構成図（Service, Kafka, ScyllaDBの関係）を `README.md` に追加すると、プロジェクトの全体像がより伝わりやすくなります。

### 5.2. APIドキュメント
*   **現状:** `payment-service` のエンドポイント仕様がコード内にしかありません。
*   **改善案:** **Springdoc OpenAPI** (Swagger UI) を導入し、起動時に自動的にAPIドキュメントが生成・公開されるようにします（`http://localhost:8080/swagger-ui.html`）。

## 6. 推奨されるロードマップ

1.  **Phase 1 (Quick Wins):**
    *   `Makefile` の追加。
    *   DockerfileのMulti-stage化。
    *   パッケージ名の修正とJava Recordsの導入（[Java Improvements](./java_improvements.md) 参照）。
2.  **Phase 2 (Infrastructure):**
    *   Docker Composeファイルの重複排除。
    *   Springdoc OpenAPIの導入。
3.  **Phase 3 (Robustness):**
    *   Kafka Consumerの並行処理化（Virtual Threads）。
    *   CI/CDパイプライン（GitHub Actions）の構築。

これらの改善を実施することで、`fraudx` はより堅牢で、開発しやすく、本番運用に耐えうるシステムへと進化します。
