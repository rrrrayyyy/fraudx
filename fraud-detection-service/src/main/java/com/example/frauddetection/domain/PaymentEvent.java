package com.example.frauddetection.domain;

import com.example.proto.*;

public record PaymentEvent(String transactionId, String userId) {
    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        this(key.getTransactionId(), val.getAccount().getUserId());
    }
}
