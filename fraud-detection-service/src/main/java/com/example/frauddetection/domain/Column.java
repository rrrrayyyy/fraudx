package com.example.frauddetection.domain;

public class Column {
    protected final String name;
    protected final String type;

    public Column(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String toDDL() {
        return name + " " + type;
    }

}
