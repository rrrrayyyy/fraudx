package com.example.frauddetection.domain;

import java.util.*;
import java.util.stream.Collectors;

import com.example.proto.Event.*;

public record PaymentEvent(String transactionId, String userId) {
    public static final String TABLE_NAME = "payment_events";

    private static final Column<?>[] COLUMNS = new Column[] {
            new PrimaryKey<>("transaction_id", DataType.TEXT, null),
            new Column<>("user_id", DataType.TEXT, null)
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
        return String.format("CREATE TABLE IF NOT EXISTS %s (%s) " +
                "WITH compaction = { 'class': 'TimeWindowCompactionStrategy', 'compaction_window_size': '1', 'compaction_window_unit': 'DAYS' }",
                PaymentEvent.TABLE_NAME,
                Arrays.stream(COLUMNS).map(Column::toDDL).collect(Collectors.joining(", ")));
    }

    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        this(key.getTransactionId(), val.getAccount().getUserId());
    }
}
