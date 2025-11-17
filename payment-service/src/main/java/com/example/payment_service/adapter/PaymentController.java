package com.example.payment_service.adapter;

import org.springframework.web.bind.annotation.*;

import com.example.payment_service.usecase.PaymentEventsProduceUseCase;

@RestController
public class PaymentController {
	private final PaymentEventsProduceUseCase paymentEventsProduceUseCase;

	public PaymentController(PaymentEventsProduceUseCase paymentEventsProduceUseCase) {
		this.paymentEventsProduceUseCase = paymentEventsProduceUseCase;
	}

	@PostMapping("payment-events")
	public void publishPaymentEvents(
			@RequestParam(defaultValue = "false") boolean logging,
			@RequestParam(defaultValue = "10000") int n) {
		paymentEventsProduceUseCase.run(logging, n);
	}
}
