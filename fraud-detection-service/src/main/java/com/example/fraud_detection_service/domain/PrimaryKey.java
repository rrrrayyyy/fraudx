package com.example.fraud_detection_service.domain;

public class PrimaryKey<T> extends Column<T> {
    public PrimaryKey(String columnName, DataType type, T value) {
        super(columnName, type, value);
    }

    public String getName() {
        return super.getName();
    }

    public String toDDL() {
        return super.toDDL() + " PRIMARY KEY";
    }
}
