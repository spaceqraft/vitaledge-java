package com.vitaledge.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record QueryResult(
    List<String> columns,
    List<Map<String, Object>> rows,
    QueryStats stats,
    List<Diagnostic> warnings) {

  public QueryResult {
    columns = List.copyOf(columns);
    rows = wrapRows(rows);
    warnings = List.copyOf(warnings);
  }

  private static List<Map<String, Object>> wrapRows(List<Map<String, Object>> rows) {
    List<Map<String, Object>> wrappedRows = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      wrappedRows.add(Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row)));
    }
    return Collections.unmodifiableList(wrappedRows);
  }
}