package com.example.fraud_detection_service.domain;

import org.springframework.data.cassandra.core.mapping.*;

@Table("payment_events")
public class PaymentEvent {
    @PrimaryKey("transaction_id")
    private String transactionId;

    @Column("user_id")
    private String userId;

    public PaymentEvent(String tid, String uid) {
        transactionId = tid;
        userId = uid;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String tid) {
        transactionId = tid;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String uid) {
        userId = uid;
    }
}
