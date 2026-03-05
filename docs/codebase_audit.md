# Codebase Audit Report

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
