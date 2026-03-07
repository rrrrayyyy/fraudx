## 5. Testing & Quality Assurance
- [ ] **Test Coverage**
    - [ ] Inventory existing unit tests in `src/test`.
    - [ ] Check for integration tests (Testcontainers, embedded Kafka/Cassandra).
- [ ] **Static Analysis**
    - [ ] Check for Checkstyle/SpotBugs/SonarQube integration.
    - [ ] Review `.proto` file linting/breaking change detection.

## 6. Observability
- [ ] **Metrics**
    - [ ] Verify Micrometer/Prometheus integration in code.
    - [ ] Review `monitoring/prometheus/prometheus.yml` scrape configs.
    - [ ] Check JMX Exporter settings (`kafka-config.yaml`).
- [ ] **Tracing**
    - [ ] Check for distributed tracing (OpenTelemetry/Zipkin) implementation.

## 7. Security
- [ ] **Secrets Management**
    - [ ] Check for hardcoded credentials in `application.yaml` or code.
- [ ] **Network Security**
    - [ ] Review inter-service communication (TLS/mTLS).
    - [ ] Check API authentication/authorization mechanisms.
