package com.example.payment.adapter;

import org.springframework.web.bind.annotation.*;

import com.example.payment.usecase.PaymentEventsProduceUseCase;

@RestController
public class PaymentController {
	private final PaymentEventsProduceUseCase paymentEventsProduceUseCase;

	public PaymentController(PaymentEventsProduceUseCase paymentEventsProduceUseCase) {
		this.paymentEventsProduceUseCase = paymentEventsProduceUseCase;
	}

	@PostMapping("payment-events")
	public void publishPaymentEvents(@RequestParam(defaultValue = "10000") int n) {
		paymentEventsProduceUseCase.run(n);
	}
}
