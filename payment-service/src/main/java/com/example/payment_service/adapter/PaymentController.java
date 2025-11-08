package com.example.payment_service.adapter;

import org.springframework.web.bind.annotation.*;

import com.example.payment_service.usecase.PaymentEventsProduceUseCase;

@RestController
public class PaymentController {
    private final PaymentEventsProduceUseCase paymentEventsProduceUseCase;

    public PaymentController() {
        paymentEventsProduceUseCase = new PaymentEventsProduceUseCase();
    }

    @PostMapping("payment-events")
    public void publishPaymentEvents(@RequestParam int n) {
        paymentEventsProduceUseCase.run(n);
    }
}
