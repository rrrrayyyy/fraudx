package com.example.proto.domain;

import java.time.Instant;

import com.example.proto.*;
import com.google.protobuf.Timestamp;

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

	public static PaymentEventValue generateValue(String userId, PaymentMethod paymentMethod,
			Instant processedAt, String batchId) {
		var now = Instant.now();
		var account = Account.newBuilder()
				.setUserId(userId)
				.build();
		var processedAtTs = Timestamp.newBuilder()
				.setSeconds(processedAt.getEpochSecond())
				.setNanos(processedAt.getNano())
				.build();
		var createdAtTs = Timestamp.newBuilder()
				.setSeconds(now.getEpochSecond())
				.setNanos(now.getNano())
				.build();
		return PaymentEventValue.newBuilder()
				.setAccount(account)
				.setAmount(100)
				.setCurrency(Currency.CURRENCY_USD)
				.setPaymentMethod(paymentMethod)
				.setProcessedAt(processedAtTs)
				.setCreatedAt(createdAtTs)
				.setBatchId(batchId)
				.build();
	}
}
