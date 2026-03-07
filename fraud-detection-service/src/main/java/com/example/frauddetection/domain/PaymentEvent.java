package com.example.frauddetection.domain;

import java.util.*;
import java.util.stream.Collectors;

import com.example.proto.*;

public record PaymentEvent(String transactionId, String userId) {
    public static final String TABLE_NAME = "payment_events";

    private static final Column[] COLUMNS = new Column[] {
            new PrimaryKey("user_id", "text", false),
            new PrimaryKey("transaction_id", "text", true),
    };

    private static final String INSERT_QUERY = generateInsertInfo();
    private static final String CREATE_TABLE_QUERY = generateCreateTable();

    public static String getInsertInto() {
        return INSERT_QUERY;
    }

    public static String getCreateTable() {
        return CREATE_TABLE_QUERY;
    }

    private static String generateInsertInfo() {
        var columns = Arrays.stream(COLUMNS).map(Column::getName).collect(Collectors.joining(", "));
        var questions = String.join(", ", Collections.nCopies(COLUMNS.length, "?"));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", PaymentEvent.TABLE_NAME, columns, questions);
    }

    private static String generateCreateTable() {
        var schema = new TableSchema(PaymentEvent.TABLE_NAME, COLUMNS);
        return schema.generateCreateDDL() +
                " WITH compaction = { 'class': 'TimeWindowCompactionStrategy', 'compaction_window_size': '1', 'compaction_window_unit': 'DAYS' }";
    }

    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        this(key.getTransactionId(), val.getAccount().getUserId());
    }
}
