package com.example.kafka;

import java.util.Map;

import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.*;

import com.google.protobuf.*;

public class KafkaProtobufDeserializer<T extends Message> implements Deserializer<T> {
    private static final Logger log = LoggerFactory.getLogger(KafkaProtobufDeserializer.class);
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
            log.error("⚠️ Failed to deserialize protobuf message from topic {}: {}", topic, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs, boolean isKey) {
        var typeProp = isKey ? "protobuf.key.type" : "protobuf.value.type";
        var rawValue = configs.get(typeProp) != null ? configs.get(typeProp)
                : configs.get("spring.kafka.properties." + typeProp);
        if (rawValue == null) {
            throw new RuntimeException("❌ Failed to find Protobuf type property: " + typeProp);
        }
        try {
            var className = switch (rawValue) {
                case String s -> s;
                case Object o -> o.toString();
            };
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method parserMethod = clazz.getMethod("parser");
            this.parser = (Parser<T>) parserMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Protobuf parser for: " + rawValue, e);
        }
    }
}
