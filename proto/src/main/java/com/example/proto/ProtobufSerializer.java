package com.example.proto;

import com.google.protobuf.Message;

public class ProtobufSerializer {
    public static byte[] serialize(Message message) {
        return message == null ? null : message.toByteArray();
    }
}
