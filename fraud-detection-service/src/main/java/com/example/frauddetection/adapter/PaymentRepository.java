package com.example.frauddetection.adapter;

import org.slf4j.*;
import org.springframework.stereotype.Repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.example.frauddetection.domain.PaymentEvent;

import jakarta.annotation.PostConstruct;

@Repository
public class PaymentRepository {
    private static final Logger log = LoggerFactory.getLogger(PaymentRepository.class);

    private final CqlSession session;
    private PreparedStatement insertStmt;

    public PaymentRepository(CqlSession session) {
        this.session = session;
    }

    @PostConstruct
    public void init() {
        try {
            var createTableCql = PaymentEvent.getCreateTable();
            var insertCql = PaymentEvent.getInsertInto();
            session.execute(createTableCql);
            log.info("✅ Table {} is created", PaymentEvent.TABLE_NAME);
            this.insertStmt = session.prepare(insertCql);
            log.info("✅ Prepared insert statement successfully");
        } catch (Exception e) {
            log.error("❌ Initializing tables or preparing statements failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    public PreparedStatement getInsertStmt() {
        return insertStmt;
    }
}
