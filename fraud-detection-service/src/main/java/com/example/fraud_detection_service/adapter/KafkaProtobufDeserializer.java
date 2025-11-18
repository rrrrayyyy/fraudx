package com.example.fraud_detection_service.adapter;

import org.apache.kafka.common.serialization.Deserializer;

import com.google.protobuf.*;

public class KafkaProtobufDeserializer<T extends Message> implements Deserializer<T> {
    private Parser<T> parser = null;

    public KafkaProtobufDeserializer() {
    }

    public KafkaProtobufDeserializer(Parser<T> parser) {
        this.parser = parser;
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return parser.parseFrom(data);
        } catch (Exception e) {
            throw new RuntimeException("❌ Failed to deserialize Protobuf message", e);
        }
    }
}
