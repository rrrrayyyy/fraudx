package com.example.payment_service.usecase;

import java.util.concurrent.*;

import org.springframework.stereotype.Service;

@Service
public class PaymentEventsProduceUseCase {
    private final KafkaProducer kafkaProducer;

    public PaymentEventsProduceUseCase(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    public void run(int n) {
        System.out.println("PaymentEventsProduceUseCase.run() started with: " + n);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int id = i;
                executor.submit(() -> {
                    System.out.println("🧵 Task " + id + " running on " + Thread.currentThread());
                    kafkaProducer.send("payment", "payment event id: " + id);
                    return null;
                });
            }
        }
    }
}
