package com.vitaledge.client;

import com.google.protobuf.ByteString;
import java.util.List;

public record ExplainResult(
    ByteString explainJson,
    QueryStats stats,
    List<Diagnostic> warnings) {

  public ExplainResult {
    warnings = List.copyOf(warnings);
  }
}