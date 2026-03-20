package com.example.frauddetection.adapter;

import java.util.*;

public class TableSchema {
    private final String tableName;
    private final List<Column> columns;

    public TableSchema(String tableName, Column[] columns) {
        this.tableName = tableName;
        this.columns = List.of(columns);
    }

    public String generateCreateDDL() {
        List<String> definitions = new ArrayList<>();
        List<String> partitionKeys = new ArrayList<>();
        List<String> clusteringColumns = new ArrayList<>();

        for (Column col : columns) {
            definitions.add(col.toDDL());
            if (col instanceof PrimaryKey) {
                var pk = (PrimaryKey) col;
                if (pk.isClustering()) {
                    clusteringColumns.add(pk.getName());
                } else {
                    partitionKeys.add(pk.getName());
                }
            }
        }

        var pkSection = new StringBuilder("PRIMARY KEY (");
        if (partitionKeys.size() > 1) {
            pkSection.append("(").append(String.join(", ", partitionKeys)).append(")");
        } else {
            pkSection.append(partitionKeys.get(0));
        }

        if (!clusteringColumns.isEmpty()) {
            pkSection.append(", ").append(String.join(", ", clusteringColumns));
        }
        pkSection.append(")");

        definitions.add(pkSection.toString());

        return String.format("CREATE TABLE IF NOT EXISTS %s (\n %s\n)", tableName, String.join(",\n ", definitions));
    }
}
