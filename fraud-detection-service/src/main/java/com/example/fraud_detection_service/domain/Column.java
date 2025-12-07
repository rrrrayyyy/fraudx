package com.example.fraud_detection_service.domain;

public class Column<T> {
    protected final String name;
    protected final DataType type;
    protected T value;

    public Column(String name, DataType type, T value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String toDDL() {
        return name + " " + type.toString();
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }
}
