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

	public static PaymentEventValue generateValue(String userId, String paymentMethodId, String cardId) {
		var now = Instant.now();
		var account = Account.newBuilder()
				.setUserId(userId)
				.build();
		var paymentMethod = PaymentMethod.newBuilder()
				.setId(paymentMethodId)
				.setCardId(cardId)
				.build();
		return PaymentEventValue.newBuilder()
				.setAccount(account)
				.setPaymentMethod(paymentMethod)
				.setCreatedAt(Timestamp.newBuilder()
						.setSeconds(now.getEpochSecond())
						.setNanos(now.getNano())
						.build())
				.build();
	}
}
