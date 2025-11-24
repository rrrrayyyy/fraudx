package com.example.payment_service.adapter;

import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment_service.usecase.PaymentEventProducer;
import com.example.proto.Event.*;

@Service
public class KafkaClient implements PaymentEventProducer {
	private final KafkaTemplate<byte[], byte[]> protoTemplate;
	private final Topic paymentTopic;
	private final Serializer<PaymentEventKey> keySerializer;
	private final Serializer<PaymentEventValue> valueSerializer;

	public KafkaClient(KafkaTemplate<byte[], byte[]> protoTemplate, KafkaTopicConfig topicConfig) {
		this.protoTemplate = protoTemplate;
		paymentTopic = topicConfig.getPaymentTopic();
		keySerializer = new KafkaProtobufSerializer<>();
		valueSerializer = new KafkaProtobufSerializer<>();
	}

	@Override
	public void publish(PaymentEventKey key, PaymentEventValue value) {
		protoTemplate.send(paymentTopic.getName(),
				keySerializer.serialize(paymentTopic.getName(), key),
				valueSerializer.serialize(paymentTopic.getName(), value));
	}
}
