package com.vitaledge.client;

import java.util.List;

public record Capabilities(
    String protocolVersion,
    List<String> parserVersions,
    List<String> irVersions,
    boolean preparedQuerySupported,
    String parameterBinding) {

  public Capabilities {
    parserVersions = List.copyOf(parserVersions);
    irVersions = List.copyOf(irVersions);
  }
}