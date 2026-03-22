package com.example.frauddetection.domain;

import java.time.Instant;

import com.example.proto.*;

public record PaymentEvent(String transactionId, Instant processedAt, String cardId, String batchId) {
    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        this(
                key.getTransactionId(),
                Instant.ofEpochSecond(val.getProcessedAt().getSeconds(), val.getProcessedAt().getNanos()),
                val.getPaymentMethod().getCardId(),
                val.getBatchId());
    }
}
