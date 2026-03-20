package com.example.frauddetection.usecase;

import java.util.List;

import com.example.frauddetection.domain.PaymentEvent;

public interface PaymentEventRepository {
    List<PaymentEvent> insertAll(List<PaymentEvent> events);
}
