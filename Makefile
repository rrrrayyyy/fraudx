DC_BASE := docker compose
PAYMENT_LOG := fraudx-payment-service-1
FRAUD_LOG := fraudx-fraud-detection-service-1
SCYLLA_NODE := fraudx-scylladb-1-1

n ?= 10000000

CQL_STATS := tablestats fraudx.payment_events_by_card
CQL_SCHEMA := "DESCRIBE KEYSPACE fraudx;"
CQL_TOP10 := "SELECT * FROM fraudx.payment_events_by_card LIMIT 10;"

.PHONY: build up down down-remove logs-payment logs-fraud post-event fraud-rps cql help

up:
	./gradlew clean bootJar
	$(DC_BASE) down -v --remove-orphans
	$(DC_BASE) up --build -d
	docker logs -f $(PAYMENT_LOG)

down:
	$(DC_BASE) down

down-remove:
	$(DC_BASE) down -v --remove-orphans

logs-payment:
	docker logs -f $(PAYMENT_LOG)

logs-fraud:
	docker logs -f $(FRAUD_LOG)

post-event:
	curl -X POST "http://localhost:8080/payment-events?n=$(n)"

fraud-rps:
	$(DC_BASE) stop fraud-detection-service
	docker logs $(FRAUD_LOG) | grep RPS

cql:
	docker exec -it $(SCYLLA_NODE) nodetool -h 192.168.1.101 $(CQL_STATS)
	docker exec -it $(SCYLLA_NODE) cqlsh 192.168.1.101 9042 -e $(CQL_SCHEMA)
	docker exec -it $(SCYLLA_NODE) cqlsh 192.168.1.101 9042 -e $(CQL_TOP10)

help:
	@echo "Usage: make [command] [n=count]"
	@echo "  up           : Normal build & up"
	@echo "  post-event   : POST events (Default n=10000000. Use: make post-event n=500)"
	@echo "  fraud-rps    : Stop fraud-service & check RPS"
	@echo "  cql          : Run pre-defined CQL commands (Count, Top10...)"