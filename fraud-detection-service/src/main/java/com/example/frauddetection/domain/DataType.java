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

    private final String value;

    DataType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
