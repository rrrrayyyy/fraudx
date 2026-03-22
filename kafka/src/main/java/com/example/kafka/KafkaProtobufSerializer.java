package com.example.kafka;

import org.apache.kafka.common.serialization.Serializer;

import com.google.protobuf.Message;

public class KafkaProtobufSerializer<T extends Message> implements Serializer<T> {
	@Override
	public byte[] serialize(String topic, T data) {
		return data == null ? null : data.toByteArray();
	}
}
