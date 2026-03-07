package com.example.frauddetection.domain;

public class PrimaryKey extends Column {
    private final boolean isClustering;

    public PrimaryKey(String columnName, String type, boolean isClustering) {
        super(columnName, type);
        this.isClustering = isClustering;
    }

    public boolean isClustering() {
        return isClustering;
    }
}
