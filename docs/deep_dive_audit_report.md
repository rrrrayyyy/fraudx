# Deep Dive Codebase Audit Report

## 2. Critical Findings (Priority: High)

### 2.3. Excessive Database Timeouts
*   **Location:** `fraud-detection-service/src/main/resources/application.conf` & `scylla.yaml`
*   **Issue:** Timeouts are set to **1800 seconds (30 minutes)**.
    *   `request.timeout`, `connection.connect-timeout`, `read_request_timeout_in_ms` etc.
*   **Impact:** In case of network partitions or DB overload, the application threads will hang for 30 minutes, likely causing a cascading failure.
*   **Recommendation:** Reduce timeouts to reasonable values (e.g., 5-10 seconds for queries, 30 seconds for connections).

## 3. Infrastructure & Configuration

### 3.1. Docker Compose Inconsistency
*   **Issue:** `compose.yaml` and `metrics-compose.yaml` have divergent configurations (different network names, different memory limits for brokers).
*   **Recommendation:** Consolidate into a single `compose.yaml` with profiles (e.g., `docker compose --profile monitoring up`) or use `include`.

### 3.2. Logging Strategy
*   **Location:** `logback-spring.xml` (both services)
*   **Issue:**
    *   Logs are written to both Console and File. In containerized environments, writing to local files is often unnecessary and consumes ephemeral storage.
    *   `scan="true"` is enabled, which causes periodic I/O to check for config changes.
*   **Recommendation:** Disable file appenders and config scanning for production profiles.

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
