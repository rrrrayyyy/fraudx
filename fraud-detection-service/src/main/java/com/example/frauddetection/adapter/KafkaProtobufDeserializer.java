package com.example.frauddetection.adapter;

import java.util.Map;

import org.apache.kafka.common.serialization.Deserializer;

import com.google.protobuf.*;

public class KafkaProtobufDeserializer<T extends Message> implements Deserializer<T> {
    private Parser<T> parser;

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

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs, boolean isKey) {
        var typeProp = isKey ? "protobuf.key.type" : "protobuf.value.type";
        var typeName = configs.get(typeProp);
        if (typeName == null) {
            typeName = configs.get("spring.kafka.properties." + typeProp);
        }
        try {
            var className = typeName.toString();
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method parserMethod = clazz.getMethod("parser");
            this.parser = (Parser<T>) parserMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Protobuf parser for: " + typeName, e);
        }
    }
}
