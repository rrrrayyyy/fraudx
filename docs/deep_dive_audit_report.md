# Deep Dive Codebase Audit Report

## 4. Code Quality & Best Practices

### 4.1. Thread Management
*   **Location:** `fraud-detection-service/.../adapter/KafkaClient.java`
*   **Issue:** An unmanaged thread is created using `Executors.newSingleThreadExecutor()` to poll for topic existence in an infinite loop (`while (true)`).
*   **Recommendation:** Use `ScheduledExecutorService` or Spring's `SmartLifecycle` / `ApplicationRunner` to manage the lifecycle of this task gracefully.

### 4.2. Dependency Management
*   **Location:** `build.gradle` (subprojects)
*   **Issue:** Versions for `kafka-clients` and `protobuf-java` are hardcoded in multiple files.
*   **Recommendation:** Use a Gradle Version Catalog (`libs.versions.toml`) to centralize dependency versions.

### 4.3. Hardcoded Values
*   **Location:** `ScyllaConfiguration.java`
*   **Issue:** Port `9042` is hardcoded.
*   **Recommendation:** Externalize the port to `application.yaml`.

## 5. Testing & Observability

### 5.1. Testing Gaps
*   **Location:** `src/test/java/...`
*   **Issue:** The project currently only contains empty skeleton tests (`contextLoads`). There are no meaningful unit tests or integration tests.
*   **Recommendation:**
    *   Add unit tests for `KafkaClient` and `PaymentEventsProduceUseCase`.
    *   Implement integration tests using **Testcontainers** (Kafka & ScyllaDB) to verify end-to-end flows.

### 5.2. Observability Missing Links
*   **Location:** `build.gradle` & Application Code
*   **Issue:**
    *   Dependencies for `io.micrometer:micrometer-registry-prometheus` exist, but there is no custom instrumentation (Counters, Timers, Gauges) in the business logic.
    *   No distributed tracing (OpenTelemetry/Zipkin) is configured, making it difficult to trace a transaction from Payment Service to Fraud Detection Service.
*   **Recommendation:**
    *   Inject `MeterRegistry` and add counters for events produced/consumed and timers for processing duration.
    *   Add OpenTelemetry instrumentation to correlate logs and metrics across services.

## 6. Next Steps
1.  **Fix Critical Issues:** Address the data loss bug and timeout settings immediately.
2.  **Refactor Config:** Unify Docker Compose files and fix default application properties.
3.  **Enhance Resilience:** Implement proper error handling and retries for Kafka consumers.
4.  **Add Visibility:** Implement custom metrics and basic integration tests to ensure system stability.
