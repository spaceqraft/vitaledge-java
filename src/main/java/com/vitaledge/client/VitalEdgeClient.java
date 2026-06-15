package com.vitaledge.client;

import com.google.protobuf.ByteString;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import vitaledge.v1.Ddl;
import vitaledge.v1.DdlServiceGrpc;
import vitaledge.v1.Dml;
import vitaledge.v1.DmlServiceGrpc;

public final class VitalEdgeClient implements AutoCloseable {
  public static final String DEFAULT_HOST = "localhost";
  public static final int DEFAULT_PORT = 7443;
  public static final String DEFAULT_TENANT = "default";
  private static final String SDK_LANGUAGE = "java";
  private static final String SDK_VERSION = "0.1.0";
  private static final String PROTOCOL_VERSION = "1";

  private final String host;
  private final int port;
  private final String tenant;
  private final boolean tls;
  private final ChannelCredentials tlsCredentials;
  private final Consumer<ManagedChannelBuilder<?>> channelCustomizer;

  private ManagedChannel channel;
  private DmlServiceGrpc.DmlServiceBlockingStub stub;
  private DdlServiceGrpc.DdlServiceBlockingStub ddlStub;

  public static Builder builder() {
    return new Builder();
  }

  public VitalEdgeClient() {
    this(builder());
  }

  public VitalEdgeClient(String host, int port, String tenant) {
    this(builder().host(host).port(port).tenant(tenant));
  }

  public VitalEdgeClient(
      String host,
      int port,
      String tenant,
      boolean tls,
      ChannelCredentials tlsCredentials,
      Consumer<ManagedChannelBuilder<?>> channelCustomizer) {
    this.host = Objects.requireNonNull(host, "host");
    this.port = port;
    this.tenant = Objects.requireNonNull(tenant, "tenant");
    this.tls = tls;
    this.tlsCredentials = tlsCredentials;
    this.channelCustomizer = channelCustomizer;
    connect();
  }

  private VitalEdgeClient(Builder builder) {
    this(
        builder.host,
        builder.port,
        builder.tenant,
        builder.tls,
        builder.tlsCredentials,
        builder.channelCustomizer);
  }

  public synchronized void connect() {
    close();

    ManagedChannelBuilder<?> builder;
    if (tls) {
      ChannelCredentials credentials = tlsCredentials != null ? tlsCredentials : TlsChannelCredentials.create();
      builder = Grpc.newChannelBuilderForAddress(host, port, credentials);
    } else {
      builder = ManagedChannelBuilder.forAddress(host, port).usePlaintext();
    }

    if (channelCustomizer != null) {
      channelCustomizer.accept(builder);
    }

    channel = builder.build();
    stub = DmlServiceGrpc.newBlockingStub(channel);
    ddlStub = DdlServiceGrpc.newBlockingStub(channel);
  }

  @Override
  public synchronized void close() {
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
      stub = null;
      ddlStub = null;
    }
  }

  public QueryResult execute(String cypher) {
    return execute(cypher, null, null, false, false, false, null);
  }

  public QueryResult execute(
      String cypher,
      Map<String, ?> parameters,
      String tenant,
      boolean readOnly,
      boolean includeStats,
      boolean includeWarnings,
      Double timeoutSeconds) {
    Dml.QueryRequest request =
        buildRequest(
            cypher,
            parameters,
            tenant,
            readOnly,
            includeStats,
            includeWarnings);

    Dml.QueryResponse response =
        timeoutSeconds == null
            ? stub.execute(request)
            : stub.withDeadlineAfter(secondsToMillis(timeoutSeconds), java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(request);
    return toQueryResult(response);
  }

  public ExplainResult explain(String cypher) {
    return explain(cypher, null, null);
  }

  public ExplainResult explain(String cypher, String tenant, Double timeoutSeconds) {
    Dml.QueryRequest request = buildRequest(cypher, tenant, false, false, false);
    Dml.ExplainResponse response =
        timeoutSeconds == null
            ? stub.explain(request)
            : stub.withDeadlineAfter(secondsToMillis(timeoutSeconds), java.util.concurrent.TimeUnit.MILLISECONDS)
                .explain(request);
    return toExplainResult(response);
  }

  public Capabilities getCapabilities() {
    return getCapabilities(null);
  }

  public Capabilities getCapabilities(Double timeoutSeconds) {
    Dml.CapabilitiesResponse response =
        timeoutSeconds == null
        ? stub.getCapabilities(Dml.CapabilitiesRequest.newBuilder().build())
            : stub.withDeadlineAfter(secondsToMillis(timeoutSeconds), java.util.concurrent.TimeUnit.MILLISECONDS)
          .getCapabilities(Dml.CapabilitiesRequest.newBuilder().build());
    return new Capabilities(
        response.getProtocolVersion(),
        response.getParserVersionsList(),
        response.getIrVersionsList(),
        response.getPreparedQuerySupported(),
        response.getParameterBinding());
  }

  public CreatePropertyIndexResult createVertexPropertyIndex(String schema, String property) {
    return createVertexPropertyIndex(schema, property, null, true, null);
  }

  public CreatePropertyIndexResult createVertexPropertyIndex(
      String schema,
      String property,
      String tenant,
      boolean ifNotExists,
      Double timeoutSeconds) {
    Ddl.CreateVertexPropertyIndexRequest request =
        Ddl.CreateVertexPropertyIndexRequest.newBuilder()
            .setTenant(tenant != null ? tenant : this.tenant)
            .setSchema(Objects.requireNonNull(schema, "schema"))
            .setProperty(Objects.requireNonNull(property, "property"))
            .setIfNotExists(ifNotExists)
            .build();

    Ddl.CreateVertexPropertyIndexResponse response =
        timeoutSeconds == null
            ? ddlStub.createVertexPropertyIndex(request)
            : ddlStub.withDeadlineAfter(secondsToMillis(timeoutSeconds), java.util.concurrent.TimeUnit.MILLISECONDS)
                .createVertexPropertyIndex(request);
    return new CreatePropertyIndexResult(response.getCreated(), response.getIndexedEntities());
  }

  public CreatePropertyIndexResult createEdgePropertyIndex(String schema, String property) {
    return createEdgePropertyIndex(schema, property, null, true, null);
  }

  public CreatePropertyIndexResult createEdgePropertyIndex(
      String schema,
      String property,
      String tenant,
      boolean ifNotExists,
      Double timeoutSeconds) {
    Ddl.CreateEdgePropertyIndexRequest request =
        Ddl.CreateEdgePropertyIndexRequest.newBuilder()
            .setTenant(tenant != null ? tenant : this.tenant)
            .setSchema(Objects.requireNonNull(schema, "schema"))
            .setProperty(Objects.requireNonNull(property, "property"))
            .setIfNotExists(ifNotExists)
            .build();

    Ddl.CreateEdgePropertyIndexResponse response =
        timeoutSeconds == null
            ? ddlStub.createEdgePropertyIndex(request)
            : ddlStub.withDeadlineAfter(secondsToMillis(timeoutSeconds), java.util.concurrent.TimeUnit.MILLISECONDS)
                .createEdgePropertyIndex(request);
    return new CreatePropertyIndexResult(response.getCreated(), response.getIndexedEntities());
  }

  private Dml.QueryRequest buildRequest(
      String cypher,
      String tenant,
      boolean readOnly,
      boolean includeStats,
      boolean includeWarnings) {
    return buildRequest(cypher, null, tenant, readOnly, includeStats, includeWarnings);
  }

  private Dml.QueryRequest buildRequest(
      String cypher,
      Map<String, ?> parameters,
      String tenant,
      boolean readOnly,
      boolean includeStats,
      boolean includeWarnings) {
    Dml.QueryRequest.Builder builder = Dml.QueryRequest.newBuilder()
        .setTenant(tenant != null ? tenant : this.tenant)
        .setInput(Dml.QueryInput.newBuilder().setCypher(cypher).build())
        .setOptions(
            Dml.RequestOptions.newBuilder()
                .setReadOnly(readOnly)
                .setIncludeStats(includeStats)
                .setIncludeWarnings(includeWarnings)
                .build())
        .setClient(
            Dml.ClientContext.newBuilder()
                .setSdkLanguage(SDK_LANGUAGE)
                .setSdkVersion(SDK_VERSION)
                .setProtocolVersion(PROTOCOL_VERSION)
                .build());
    if (parameters != null && !parameters.isEmpty()) {
      for (Map.Entry<String, ?> entry : parameters.entrySet()) {
        builder.putParameters(entry.getKey(), toProtoValue(entry.getValue()));
      }
    }
    return builder.build();
  }

  private static Dml.Value toProtoValue(Object value) {
    if (value == null) {
      return Dml.Value.newBuilder().setNullValue(Dml.NullValue.newBuilder().build()).build();
    }
    if (value instanceof Boolean b) {
      return Dml.Value.newBuilder().setBoolValue(b).build();
    }
    if (value instanceof Byte b) {
      return Dml.Value.newBuilder().setIntValue(b).build();
    }
    if (value instanceof Short s) {
      return Dml.Value.newBuilder().setIntValue(s).build();
    }
    if (value instanceof Integer i) {
      return Dml.Value.newBuilder().setIntValue(i).build();
    }
    if (value instanceof Long l) {
      return Dml.Value.newBuilder().setIntValue(l).build();
    }
    if (value instanceof Float f) {
      return Dml.Value.newBuilder().setDoubleValue(f).build();
    }
    if (value instanceof Double d) {
      return Dml.Value.newBuilder().setDoubleValue(d).build();
    }
    if (value instanceof String s) {
      return Dml.Value.newBuilder().setStringValue(s).build();
    }
    if (value instanceof byte[] bytes) {
      return Dml.Value.newBuilder().setBytesValue(ByteString.copyFrom(bytes)).build();
    }
    if (value instanceof ByteString bs) {
      return Dml.Value.newBuilder().setBytesValue(bs).build();
    }
    if (value instanceof Map<?, ?> map) {
      Dml.MapValue.Builder mapBuilder = Dml.MapValue.newBuilder();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("Cypher map parameter keys must be strings");
        }
        mapBuilder.putValues(key, toProtoValue(entry.getValue()));
      }
      return Dml.Value.newBuilder().setMapValue(mapBuilder.build()).build();
    }
    if (value instanceof Iterable<?> iterable) {
      Dml.ListValue.Builder listBuilder = Dml.ListValue.newBuilder();
      for (Object item : iterable) {
        listBuilder.addValues(toProtoValue(item));
      }
      return Dml.Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    if (value.getClass().isArray()) {
      Dml.ListValue.Builder listBuilder = Dml.ListValue.newBuilder();
      int length = java.lang.reflect.Array.getLength(value);
      for (int i = 0; i < length; i++) {
        listBuilder.addValues(toProtoValue(java.lang.reflect.Array.get(value, i)));
      }
      return Dml.Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    throw new IllegalArgumentException("Unsupported Cypher parameter type: " + value.getClass().getName());
  }

  private static QueryResult toQueryResult(Dml.QueryResponse response) {
    List<String> columns = response.getColumnsList();
    List<Map<String, Object>> rows = new ArrayList<>(response.getRowsCount());
    for (Dml.Row row : response.getRowsList()) {
      rows.add(toJavaMap(row.getValuesMap()));
    }
    return new QueryResult(columns, rows, toQueryStats(response.getStats()), toDiagnostics(response.getWarningsList()));
  }

  private static ExplainResult toExplainResult(Dml.ExplainResponse response) {
    return new ExplainResult(
        response.getExplainJson(),
        toQueryStats(response.getStats()),
        toDiagnostics(response.getWarningsList()));
  }

  private static QueryStats toQueryStats(Dml.QueryStats stats) {
    return new QueryStats(stats.getRowsReturned(), stats.getDurationMs());
  }

  private static List<Diagnostic> toDiagnostics(List<Dml.Diagnostic> warnings) {
    List<Diagnostic> diagnostics = new ArrayList<>(warnings.size());
    for (Dml.Diagnostic warning : warnings) {
      diagnostics.add(new Diagnostic(warning.getCode(), warning.getMessage()));
    }
    return Collections.unmodifiableList(diagnostics);
  }

  private static Map<String, Object> toJavaMap(Map<String, Dml.Value> values) {
    Map<String, Object> result = new LinkedHashMap<>(values.size());
    for (Map.Entry<String, Dml.Value> entry : values.entrySet()) {
      result.put(entry.getKey(), valueToJava(entry.getValue()));
    }
    return result;
  }

  private static Object valueToJava(Dml.Value value) {
    return switch (value.getKindCase()) {
      case BOOL_VALUE -> value.getBoolValue();
      case INT_VALUE -> value.getIntValue();
      case DOUBLE_VALUE -> value.getDoubleValue();
      case STRING_VALUE -> value.getStringValue();
      case BYTES_VALUE -> value.getBytesValue();
      case LIST_VALUE -> {
        List<Object> items = new ArrayList<>(value.getListValue().getValuesCount());
        for (Dml.Value item : value.getListValue().getValuesList()) {
          items.add(valueToJava(item));
        }
        yield Collections.unmodifiableList(items);
      }
      case MAP_VALUE -> toJavaMap(value.getMapValue().getValuesMap());
      case NULL_VALUE, KIND_NOT_SET -> null;
    };
  }

  private static long secondsToMillis(double seconds) {
    return Math.max(1L, Math.round(seconds * 1000.0d));
  }

  private static String bindCypherParameters(String cypher, Map<String, ?> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return cypher;
    }

    String rendered = cypher;
    List<String> names = new ArrayList<>(parameters.keySet());
    names.sort(Comparator.comparingInt(String::length).reversed());
    for (String name : names) {
      rendered = rendered.replace("$" + name, cypherLiteral(parameters.get(name)));
    }
    return rendered;
  }

  private static String cypherLiteral(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue ? "true" : "false";
    }
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
      return value.toString();
    }
    if (value instanceof Float || value instanceof Double) {
      return value.toString();
    }
    if (value instanceof String stringValue) {
      return quoteString(stringValue);
    }
    if (value instanceof byte[] bytes) {
      return quoteString(java.util.Base64.getEncoder().encodeToString(bytes));
    }
    if (value instanceof ByteString byteString) {
      return quoteString(java.util.Base64.getEncoder().encodeToString(byteString.toByteArray()));
    }
    if (value instanceof Iterable<?> iterable) {
      List<String> parts = new ArrayList<>();
      for (Object item : iterable) {
        parts.add(cypherLiteral(item));
      }
      return "[" + String.join(", ", parts) + "]";
    }
    if (value.getClass().isArray()) {
      List<String> parts = new ArrayList<>();
      int length = java.lang.reflect.Array.getLength(value);
      for (int i = 0; i < length; i++) {
        parts.add(cypherLiteral(java.lang.reflect.Array.get(value, i)));
      }
      return "[" + String.join(", ", parts) + "]";
    }
    if (value instanceof Map<?, ?> map) {
      List<String> parts = new ArrayList<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("Cypher map parameter keys must be strings");
        }
        parts.add(key + ": " + cypherLiteral(entry.getValue()));
      }
      return "{" + String.join(", ", parts) + "}";
    }

    throw new IllegalArgumentException("Unsupported Cypher parameter type: " + value.getClass().getName());
  }

  private static String quoteString(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('"');
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\\' -> builder.append("\\\\");
        case '"' -> builder.append("\\\"");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (ch < 0x20) {
            builder.append(String.format("\\u%04x", (int) ch));
          } else {
            builder.append(ch);
          }
        }
      }
    }
    builder.append('"');
    return builder.toString();
  }

  public static final class Builder {
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private String tenant = DEFAULT_TENANT;
    private boolean tls;
    private ChannelCredentials tlsCredentials;
    private Consumer<ManagedChannelBuilder<?>> channelCustomizer;

    private Builder() {}

    public Builder host(String host) {
      this.host = Objects.requireNonNull(host, "host");
      return this;
    }

    public Builder port(int port) {
      this.port = port;
      return this;
    }

    public Builder tenant(String tenant) {
      this.tenant = Objects.requireNonNull(tenant, "tenant");
      return this;
    }

    public Builder tls(boolean tls) {
      this.tls = tls;
      return this;
    }

    public Builder tlsCredentials(ChannelCredentials tlsCredentials) {
      this.tlsCredentials = tlsCredentials;
      this.tls = true;
      return this;
    }

    public Builder channelCustomizer(Consumer<ManagedChannelBuilder<?>> channelCustomizer) {
      this.channelCustomizer = channelCustomizer;
      return this;
    }

    public VitalEdgeClient build() {
      return new VitalEdgeClient(this);
    }
  }
}