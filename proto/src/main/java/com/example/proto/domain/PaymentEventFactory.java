package com.example.proto.domain;

import com.example.proto.*;

public class PaymentEventFactory {
    public static PaymentEventKey generateKey(String transactionId) {
        return PaymentEventKey.newBuilder()
                .setTransactionId(transactionId)
                .build();
    }

    public static PaymentEventValue generateValue(String userId) {
        var account = Account.newBuilder()
                .setUserId(userId)
                .build();
        return PaymentEventValue.newBuilder()
                .setAccount(account)
                .build();
    }
}
