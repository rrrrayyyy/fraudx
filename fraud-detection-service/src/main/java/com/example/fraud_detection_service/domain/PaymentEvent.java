package com.example.fraud_detection_service.domain;

import java.util.*;
import java.util.stream.Collectors;

import com.example.proto.Event.*;

public class PaymentEvent {
    public static final String TABLE_NAME = "payment_events";

    public final PrimaryKey<String> transactionId = new PrimaryKey<>("transaction_id", DataType.TEXT, null);
    public final Column<String> userId = new Column<>("user_id", DataType.TEXT, null);

    public final Column<?>[] COLUMNS = new Column[] {
            transactionId,
            userId,
    };

    public String getInsertInto() {
        var columns = Arrays.stream(COLUMNS).map(Column::getName).collect(Collectors.joining(", "));
        var questions = String.join(", ", Collections.nCopies(COLUMNS.length, "?"));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", PaymentEvent.TABLE_NAME, columns, questions);

    }

    public String getCreateTable() {
        return String.format("CREATE TABLE IF NOT EXISTS %s (%s) " +
                "WITH compaction = { 'class': 'TimeWindowCompactionStrategy', 'compaction_window_size': '1', 'compaction_window_unit': 'DAYS' }",
                PaymentEvent.TABLE_NAME,
                Arrays.stream(COLUMNS).map(Column::toDDL).collect(Collectors.joining(", ")));
    }

    public PaymentEvent() {
    }

    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        transactionId.setValue(key.getTransactionId());
        userId.setValue(val.getAccount().getUserId());
    }
}
