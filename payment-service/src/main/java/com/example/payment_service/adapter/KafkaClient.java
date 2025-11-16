package com.example.payment_service.adapter;

import org.slf4j.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.Payment.PaymentEventValue;
import com.example.payment_service.usecase.PaymentEventProducer;

@Service
public class KafkaClient implements PaymentEventProducer {
	private static final Logger log = LoggerFactory.getLogger("payment-service");
	private final KafkaTopicConfig topicConfig;
	private final KafkaTemplate<String, PaymentEventValue> protoTemplate;

	public KafkaClient(
			KafkaTopicConfig topicConfig,
			KafkaTemplate<String, PaymentEventValue> protoTemplate) {
		this.topicConfig = topicConfig;
		this.protoTemplate = protoTemplate;
	}

	@Override
	public void publish(PaymentEventValue value) {
		var t = topicConfig.getTopicName(KafkaTopic.PAYMENT.key());
		protoTemplate.send(t, value);
		log.info("✅ Published payment event: {}", value);
	}
}
