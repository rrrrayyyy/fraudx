package com.example.payment_service.adapter;

import org.springframework.web.bind.annotation.*;

import com.example.payment_service.usecase.PaymentEventsPublishUseCase;

@RestController
public class PaymentController {
    private final PaymentEventsPublishUseCase paymentEventsPublishUseCase;

    public PaymentController() {
        paymentEventsPublishUseCase = new PaymentEventsPublishUseCase();
    }

    @PostMapping("payment-events")
    public void publishPaymentEvents(@RequestParam int n) {
        paymentEventsPublishUseCase.run(n);
    }
}
