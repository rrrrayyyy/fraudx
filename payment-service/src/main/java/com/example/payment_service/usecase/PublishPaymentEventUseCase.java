package com.example.payment_service.usecase;

import java.util.concurrent.*;

public class PublishPaymentEventUseCase {
    public void run(int n) {
        System.out.println("PublishPaymentEventUseCase.run() started with: " + n);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int id = i;
                executor.submit(() -> {
                    System.out.println("Task " + id + " running on " + Thread.currentThread());
                    return null;
                });
            }
        }
    }
}
