package com.example.payment_service.adapter;

import org.springframework.web.bind.annotation.*;

import com.example.payment_service.usecase.PublishPaymentEventUseCase;

@RestController
public class PaymentController {
    private final PublishPaymentEventUseCase publishPaymentEventUsecase;

    public PaymentController() {
        publishPaymentEventUsecase = new PublishPaymentEventUseCase();
    }

    @PostMapping("publish-payment-event")
    public void publishPaymentEvent(@RequestParam int n) {
        publishPaymentEventUsecase.run(n);
    }
}
