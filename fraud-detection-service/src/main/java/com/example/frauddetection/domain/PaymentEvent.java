package com.example.frauddetection.domain;

import java.time.Instant;

import com.example.proto.*;

public record PaymentEvent(String transactionId, Instant processedAt, Instant createdAt, String cardId) {
    public PaymentEvent(PaymentEventKey key, PaymentEventValue val) {
        this(
                key.getTransactionId(),
                Instant.ofEpochSecond(val.getProcessedAt().getSeconds(), val.getProcessedAt().getNanos()),
                Instant.ofEpochSecond(val.getCreatedAt().getSeconds(), val.getCreatedAt().getNanos()),
                val.getPaymentMethod().getCardId());
    }
}
