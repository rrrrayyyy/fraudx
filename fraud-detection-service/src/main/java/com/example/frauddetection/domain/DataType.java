package com.example.frauddetection.domain;

public enum DataType {
    TEXT("text"),
    BOOLEAN("boolean"),
    INT("int"),
    BIGINT("bigint"),
    DECIMAL("decimal"),
    TIMEUUID("timeuuid"),
    TIMESTAMP("timestamp"),
    DATE("date");

    private final String type;

    DataType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return type;
    }
}
