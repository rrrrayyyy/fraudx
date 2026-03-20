package com.example.frauddetection.adapter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.*;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.example.frauddetection.domain.PaymentEvent;
import com.example.frauddetection.usecase.PaymentEventRepository;

import jakarta.annotation.PostConstruct;

@Repository
public class PaymentEventScyllaRepository implements PaymentEventRepository {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventScyllaRepository.class);
    private static final String TABLE_NAME = "payment_events";

    private static final Column[] COLUMNS = new Column[] {
            new PrimaryKey("user_id", "text", false),
            new PrimaryKey("transaction_id", "text", true),
    };

    private final CqlSession session;
    private PreparedStatement insertStmt;

    public PaymentEventScyllaRepository(CqlSession session) {
        this.session = session;
    }

    @PostConstruct
    public void init() {
        try {
            var createTableCql = generateCreateTable();
            var insertCql = generateInsertInto();
            session.execute(createTableCql);
            log.info("✅ Table {} is created", TABLE_NAME);
            this.insertStmt = session.prepare(
                    SimpleStatement.newInstance(insertCql).setIdempotent(true));
            log.info("✅ Prepared insert statement successfully");
        } catch (Exception e) {
            log.error("❌ Initializing tables or preparing statements failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<PaymentEvent> insertAll(List<PaymentEvent> events) {
        var futures = new CompletableFuture<?>[events.size()];
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            futures[i] = session.executeAsync(insertStmt.bind(event.userId(), event.transactionId()))
                    .toCompletableFuture()
                    .handle((result, ex) -> ex);
        }
        var failed = new ArrayList<PaymentEvent>();
        for (int i = 0; i < futures.length; i++) {
            if (futures[i].join() != null) {
                failed.add(events.get(i));
            }
        }
        return failed;
    }

    private static String generateInsertInto() {
        var columns = Arrays.stream(COLUMNS).map(Column::getName).collect(Collectors.joining(", "));
        var questions = String.join(", ", Collections.nCopies(COLUMNS.length, "?"));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", TABLE_NAME, columns, questions);
    }

    private static String generateCreateTable() {
        var schema = new TableSchema(TABLE_NAME, COLUMNS);
        return schema.generateCreateDDL() +
                " WITH compaction = { 'class': 'TimeWindowCompactionStrategy', 'compaction_window_size': '1', 'compaction_window_unit': 'DAYS' }";
    }
}
