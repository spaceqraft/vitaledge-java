package examples;

import com.vitaledge.client.QueryResult;
import com.vitaledge.client.VitalEdgeClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AdvancedCyberThreatDetection {
  private static final Set<String> REQUIRED_COLUMNS = Set.of(
      "Timestamp",
      "Source_IP",
      "Destination_IP",
      "Protocol",
      "Packet_Length",
      "Duration",
      "Source_Port",
      "Destination_Port",
      "Bytes_Sent",
      "Bytes_Received",
      "Flags",
      "Flow_Packets/s",
      "Flow_Bytes/s",
      "Avg_Packet_Size",
      "Total_Fwd_Packets",
      "Total_Bwd_Packets",
      "Fwd_Header_Length",
      "Bwd_Header_Length",
      "Sub_Flow_Fwd_Bytes",
      "Sub_Flow_Bwd_Bytes",
      "Inbound",
      "Attack_Type",
      "Label");

  private AdvancedCyberThreatDetection() {}

  private record Config(
      Path csv,
      String host,
      int port,
      String tenant,
      int batchSize,
      double threshold,
      int limit) {}

  private record FlowRecord(
      int flowId,
      String timestamp,
      String sourceIp,
      String destinationIp,
      String protocol,
      double packetLength,
      double durationS,
      int sourcePort,
      int destinationPort,
      double bytesSent,
      double bytesReceived,
      String flags,
      double flowPacketsPerS,
      double flowBytesPerS,
      double avgPacketSize,
      double totalFwdPackets,
      double totalBwdPackets,
      double fwdHeaderLength,
      double bwdHeaderLength,
      double subFlowFwdBytes,
      double subFlowBwdBytes,
      int inbound,
      String attackType,
      int label) {

    Map<String, Object> toPayload() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("flow_id", flowId);
      payload.put("timestamp", timestamp);
      payload.put("source_ip", sourceIp);
      payload.put("destination_ip", destinationIp);
      payload.put("protocol", protocol);
      payload.put("packet_length", packetLength);
      payload.put("duration_s", durationS);
      payload.put("source_port", sourcePort);
      payload.put("destination_port", destinationPort);
      payload.put("bytes_sent", bytesSent);
      payload.put("bytes_received", bytesReceived);
      payload.put("flags", flags);
      payload.put("flow_packets_per_s", flowPacketsPerS);
      payload.put("flow_bytes_per_s", flowBytesPerS);
      payload.put("avg_packet_size", avgPacketSize);
      payload.put("total_fwd_packets", totalFwdPackets);
      payload.put("total_bwd_packets", totalBwdPackets);
      payload.put("fwd_header_length", fwdHeaderLength);
      payload.put("bwd_header_length", bwdHeaderLength);
      payload.put("sub_flow_fwd_bytes", subFlowFwdBytes);
      payload.put("sub_flow_bwd_bytes", subFlowBwdBytes);
      payload.put("inbound", inbound);
      payload.put("attack_type", attackType);
      payload.put("label", label);
      return payload;
    }
  }

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);
    List<FlowRecord> records = loadRecords(cfg.csv());
    if (records.isEmpty()) {
      throw new IllegalStateException("No rows found in CSV");
    }

    try (VitalEdgeClient client = VitalEdgeClient.builder()
        .host(cfg.host())
        .port(cfg.port())
        .tenant(cfg.tenant())
        .build()) {
      System.out.println("Loaded " + records.size() + " flow rows from " + cfg.csv());
      System.out.println("Resetting graph and ingesting flow data...");
      resetGraph(client);
      ingestFlows(client, records, cfg.batchSize());

      System.out.println("Scoring threats in VitalEdge (without Attack_Type/Label features)...");
      scoreThreats(client, cfg.threshold());

      runHuntingQueries(client, cfg.limit());
      evaluateAgainstLabels(client, cfg.limit());
    }
  }

  private static Config parseArgs(String[] args) {
    Map<String, String> values = parseFlags(args);

    String csvValue = values.get("csv");
    if (csvValue == null || csvValue.isBlank()) {
      throw new IllegalArgumentException("Missing required --csv argument");
    }

    return new Config(
        Path.of(csvValue),
        values.getOrDefault("host", "localhost"),
        parseInt(values.getOrDefault("port", "7443"), 7443),
        values.getOrDefault("tenant", "cyberthreat"),
        Math.max(1, parseInt(values.getOrDefault("batch-size", "250"), 250)),
        parseDouble(values.getOrDefault("threshold", "0.9"), 0.9),
        Math.max(1, parseInt(values.getOrDefault("limit", "20"), 20)));
  }

  private static Map<String, String> parseFlags(String[] args) {
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        continue;
      }
      String key = arg.substring(2);
      if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
        out.put(key, "true");
      } else {
        out.put(key, args[++i]);
      }
    }
    return out;
  }

  private static List<FlowRecord> loadRecords(Path csvPath) throws IOException {
    if (!Files.exists(csvPath)) {
      throw new IOException("CSV not found: " + csvPath);
    }

    List<FlowRecord> records = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return records;
      }

      List<String> headers = parseCsvLine(headerLine);
      Set<String> headerSet = new HashSet<>(headers);
      Set<String> missing = new HashSet<>(REQUIRED_COLUMNS);
      missing.removeAll(headerSet);
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException("CSV missing required columns: " + String.join(", ", missing));
      }

      String rowLine;
      while ((rowLine = reader.readLine()) != null) {
        List<String> row = parseCsvLine(rowLine);
        Map<String, String> rowMap = rowToMap(headers, row);
        int flowId = records.size();
        records.add(new FlowRecord(
            flowId,
            text(rowMap.get("Timestamp"), ""),
            text(rowMap.get("Source_IP"), "unknown"),
            text(rowMap.get("Destination_IP"), "unknown"),
            text(rowMap.get("Protocol"), "UNKNOWN"),
            parseDouble(rowMap.get("Packet_Length"), 0.0),
            parseDouble(rowMap.get("Duration"), 0.0),
            parseInt(rowMap.get("Source_Port"), 0),
            parseInt(rowMap.get("Destination_Port"), 0),
            parseDouble(rowMap.get("Bytes_Sent"), 0.0),
            parseDouble(rowMap.get("Bytes_Received"), 0.0),
            text(rowMap.get("Flags"), ""),
            parseDouble(rowMap.get("Flow_Packets/s"), 0.0),
            parseDouble(rowMap.get("Flow_Bytes/s"), 0.0),
            parseDouble(rowMap.get("Avg_Packet_Size"), 0.0),
            parseDouble(rowMap.get("Total_Fwd_Packets"), 0.0),
            parseDouble(rowMap.get("Total_Bwd_Packets"), 0.0),
            parseDouble(rowMap.get("Fwd_Header_Length"), 0.0),
            parseDouble(rowMap.get("Bwd_Header_Length"), 0.0),
            parseDouble(rowMap.get("Sub_Flow_Fwd_Bytes"), 0.0),
            parseDouble(rowMap.get("Sub_Flow_Bwd_Bytes"), 0.0),
            parseInt(rowMap.get("Inbound"), 0),
            text(rowMap.get("Attack_Type"), "Unknown"),
            parseInt(rowMap.get("Label"), 0)));
      }
    }
    return records;
  }

  private static void resetGraph(VitalEdgeClient client) {
    client.execute("""
      MATCH (f:Host|Flow)
      DETACH DELETE f
      """);

      client.createVertexPropertyIndex("Host", "ip");
      client.createVertexPropertyIndex("Flow", "protocol");
      client.createVertexPropertyIndex("Flow", "detected_malicious");
      client.createVertexPropertyIndex("Flow", "suspicious_flows");
      client.createVertexPropertyIndex("Flow", "distinct_targets");
      client.createVertexPropertyIndex("Flow", "distinct_ports");
  }

  private static void ingestFlows(VitalEdgeClient client, List<FlowRecord> records, int batchSize) {
    String ingestQuery = """
      UNWIND $events AS e
      MERGE (src:Host {ip: e.source_ip})
      MERGE (dst:Host {ip: e.destination_ip})
      CREATE (f:Flow {
          flow_id: e.flow_id,
          timestamp: e.timestamp,
          protocol: e.protocol,
          flags: e.flags,
          packet_length: e.packet_length,
          duration_s: e.duration_s,
          source_port: e.source_port,
          destination_port: e.destination_port,
          bytes_sent: e.bytes_sent,
          bytes_received: e.bytes_received,
          flow_packets_per_s: e.flow_packets_per_s,
          flow_bytes_per_s: e.flow_bytes_per_s,
          avg_packet_size: e.avg_packet_size,
          total_fwd_packets: e.total_fwd_packets,
          total_bwd_packets: e.total_bwd_packets,
          fwd_header_length: e.fwd_header_length,
          bwd_header_length: e.bwd_header_length,
          sub_flow_fwd_bytes: e.sub_flow_fwd_bytes,
          sub_flow_bwd_bytes: e.sub_flow_bwd_bytes,
          inbound: e.inbound,
          attack_type: e.attack_type,
          label: e.label
      })
      MERGE (src)-[:SENT]->(f)
      MERGE (f)-[:TO]->(dst)
      MERGE (src)-[:COMMUNICATES_WITH]->(dst)
      """;

    for (int start = 0; start < records.size(); start += batchSize) {
      int end = Math.min(start + batchSize, records.size());
      List<Map<String, Object>> payload = new ArrayList<>(end - start);
      for (int i = start; i < end; i++) {
        payload.add(records.get(i).toPayload());
      }
      client.execute(ingestQuery, Map.of("events", payload), null, false, false, false, null);
    }
  }

  private static void scoreThreats(VitalEdgeClient client, double threshold) {
    String updateQuery = """
      MATCH (f:Flow)
      WITH
          f.protocol AS protocol,
          avg(f.bytes_sent) AS mean_bytes_sent,
          stDev(f.bytes_sent) AS stdev_bytes_sent,
          avg(f.bytes_received) AS mean_bytes_received,
          stDev(f.bytes_received) AS stdev_bytes_received,
          avg(f.flow_packets_per_s) AS mean_pps,
          stDev(f.flow_packets_per_s) AS stdev_pps,
          avg(f.flow_bytes_per_s) AS mean_bps,
          stDev(f.flow_bytes_per_s) AS stdev_bps,
          avg(f.packet_length) AS mean_packet_length,
          stDev(f.packet_length) AS stdev_packet_length
      MATCH (f:Flow)
      WHERE f.protocol = protocol
      WITH
          f,
          abs((f.bytes_sent - mean_bytes_sent) / stdev_bytes_sent) AS z_bytes_sent,
          abs((f.bytes_received - mean_bytes_received) / stdev_bytes_received) AS z_bytes_received,
          abs((f.flow_packets_per_s - mean_pps) / stdev_pps) AS z_pps,
          abs((f.flow_bytes_per_s - mean_bps) / stdev_bps) AS z_bps,
          abs((f.packet_length - mean_packet_length) / stdev_packet_length) AS z_packet_length
      WITH f, (z_bytes_sent + z_bytes_received + z_pps + z_bps + z_packet_length) / 5.0 AS threat_score
      SET f.threat_score = threat_score,
            f.detected_malicious = CASE WHEN threat_score >= $threshold THEN true ELSE false END,
            f.model_version = "vitaledge-rulegraph-v3-cypher-anomaly"
      RETURN count(f) AS updated_flows
      """;
    client.execute(updateQuery, Map.of("threshold", threshold), null, false, false, false, null);
  }

  private static void runHuntingQueries(VitalEdgeClient client, int limit) {
    int limitValue = Math.max(1, limit);
    String huntingQuery = """
      MATCH (src:Host)-[:SENT]->(f:Flow)
      WHERE f.detected_malicious = true
      RETURN "Top Suspicious Sources" AS report,
             src.ip AS source_ip,
             null AS destination_ip,
             count(f) AS suspicious_flows,
             null AS inbound_suspicious_flows,
             null AS distinct_targets,
             null AS distinct_ports,
             null AS distinct_sources,
             avg(f.threat_score) AS avg_score,
             max(f.threat_score) AS max_score
      ORDER BY suspicious_flows DESC, avg_score DESC
      LIMIT $limit_value
      UNION ALL
      MATCH (src:Host)-[:SENT]->(f:Flow)-[:TO]->(dst:Host)
      WHERE f.detected_malicious = true
      WITH src,
           count(f) AS suspicious_flows,
           count(DISTINCT dst.ip) AS distinct_targets,
           count(DISTINCT f.destination_port) AS distinct_ports,
           avg(f.threat_score) AS avg_score
      WHERE suspicious_flows >= 8 AND distinct_targets >= 4 AND distinct_ports >= 3
      RETURN "Possible Lateral Movement" AS report,
             src.ip AS source_ip,
             null AS destination_ip,
             suspicious_flows,
             null AS inbound_suspicious_flows,
             distinct_targets,
             distinct_ports,
             null AS distinct_sources,
             avg_score AS avg_score,
             null AS max_score
      ORDER BY distinct_targets DESC, avg_score DESC
      LIMIT $limit_value
      UNION ALL
      MATCH (src:Host)-[:SENT]->(f:Flow)-[:TO]->(dst:Host)
      WHERE f.detected_malicious = true
      RETURN "Destination Concentration" AS report,
             null AS source_ip,
             dst.ip AS destination_ip,
             null AS suspicious_flows,
             count(f) AS inbound_suspicious_flows,
             null AS distinct_targets,
             null AS distinct_ports,
             count(DISTINCT src.ip) AS distinct_sources,
             avg(f.threat_score) AS avg_score,
             null AS max_score
      ORDER BY inbound_suspicious_flows DESC, distinct_sources DESC
      LIMIT $limit_value
      """;

    QueryResult result = client.execute(
        huntingQuery,
        Map.of("limit_value", limitValue),
        null,
        false,
        true,
        false,
        null);

    List<Map<String, Object>> topSources = new ArrayList<>();
    List<Map<String, Object>> lateralMovement = new ArrayList<>();
    List<Map<String, Object>> destinationConcentration = new ArrayList<>();

    for (Map<String, Object> row : result.rows()) {
      String report = String.valueOf(row.getOrDefault("report", ""));
      if ("Top Suspicious Sources".equals(report)) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("source_ip", row.get("source_ip"));
        shaped.put("suspicious_flows", row.get("suspicious_flows"));
        shaped.put("avg_score", row.get("avg_score"));
        shaped.put("max_score", row.get("max_score"));
        topSources.add(shaped);
      } else if ("Possible Lateral Movement".equals(report)) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("source_ip", row.get("source_ip"));
        shaped.put("suspicious_flows", row.get("suspicious_flows"));
        shaped.put("distinct_targets", row.get("distinct_targets"));
        shaped.put("distinct_ports", row.get("distinct_ports"));
        shaped.put("avg_score", row.get("avg_score"));
        lateralMovement.add(shaped);
      } else if ("Destination Concentration".equals(report)) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("destination_ip", row.get("destination_ip"));
        shaped.put("inbound_suspicious_flows", row.get("inbound_suspicious_flows"));
        shaped.put("distinct_sources", row.get("distinct_sources"));
        shaped.put("avg_score", row.get("avg_score"));
        destinationConcentration.add(shaped);
      }
    }

    printRows("Top Suspicious Sources", topSources);
    printRows("Possible Lateral Movement", lateralMovement);
    printRows("Destination Concentration", destinationConcentration);
  }

  private static void evaluateAgainstLabels(VitalEdgeClient client, int limit) {
    String confusionQuery = """
      MATCH (f:Flow)
      RETURN
        sum(CASE WHEN f.detected_malicious = true AND f.label = 1 THEN 1 ELSE 0 END) AS tp,
        sum(CASE WHEN f.detected_malicious = true AND f.label = 0 THEN 1 ELSE 0 END) AS fp,
        sum(CASE WHEN f.detected_malicious = false AND f.label = 1 THEN 1 ELSE 0 END) AS fn,
        sum(CASE WHEN f.detected_malicious = false AND f.label = 0 THEN 1 ELSE 0 END) AS tn
      """;

    List<Map<String, Object>> confusionRows = client.execute(confusionQuery).rows();
    List<Map<String, Object>> confusion = new ArrayList<>();
    if (!confusionRows.isEmpty()) {
      Map<String, Object> row = confusionRows.get(0);
      int tp = asInt(row.get("tp"));
      int fp = asInt(row.get("fp"));
      int fn = asInt(row.get("fn"));
      int tn = asInt(row.get("tn"));
      confusion.add(Map.of(
          "tp", tp,
          "fp", fp,
          "fn", fn,
          "tn", tn,
          "precision", round4(tp + fp == 0 ? 0.0 : ((double) tp / (tp + fp))),
          "recall", round4(tp + fn == 0 ? 0.0 : ((double) tp / (tp + fn))),
          "f1", round4((2 * tp + fp + fn) == 0 ? 0.0 : (2.0 * tp / (2 * tp + fp + fn)))));
    }

    String attackSummaryQuery = """
      MATCH (f:Flow)
      RETURN f.attack_type AS attack_type,
          count(*) AS total,
          sum(CASE WHEN f.label = 1 THEN 1 ELSE 0 END) AS labeled_malicious,
          sum(CASE WHEN f.detected_malicious = true THEN 1 ELSE 0 END) AS detected_malicious,
          avg(f.threat_score) AS avg_score
      ORDER BY avg_score DESC
      """;

    List<Map<String, Object>> attackRows = client.execute(attackSummaryQuery).rows();
    List<Map<String, Object>> breakdown = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, attackRows.size()); i++) {
      Map<String, Object> row = attackRows.get(i);
      String attackType = String.valueOf(row.getOrDefault("attack_type", "Unknown"));
      int total = asInt(row.get("total"));
      int detected = asInt(row.get("detected_malicious"));

      Map<String, Object> enriched = new LinkedHashMap<>();
      enriched.put("attack_type", attackType);
      enriched.put("total", total);
      enriched.put("labeled_malicious", asInt(row.get("labeled_malicious")));
      enriched.put("detected_malicious", detected);
      enriched.put("avg_score", row.get("avg_score"));
      enriched.put("detected_rate", round4(total == 0 ? 0.0 : ((double) detected / total)));
      breakdown.add(enriched);
    }

    printRows("Evaluation vs Held-Out Labels", confusion);
    printRows("Attack-Type Comparison (Post-Hoc Only)", breakdown);
  }

  private static void printRows(String title, List<Map<String, Object>> rows) {
    System.out.println("\n=== " + title + " ===");
    if (rows.isEmpty()) {
      System.out.println("No rows returned");
      return;
    }
    for (Map<String, Object> row : rows) {
      System.out.println(row);
    }
  }

  private static Map<String, String> rowToMap(List<String> headers, List<String> row) {
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      out.put(headers.get(i), i < row.size() ? row.get(i) : "");
    }
    return out;
  }

  private static List<String> parseCsvLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ',' && !inQuotes) {
        values.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    values.add(current.toString());
    return values;
  }

  private static String text(String value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }

  private static int parseInt(String value, int defaultValue) {
    try {
      return (int) Double.parseDouble(value == null ? String.valueOf(defaultValue) : value.trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static double parseDouble(String value, double defaultValue) {
    try {
      return Double.parseDouble(value == null ? String.valueOf(defaultValue) : value.trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static int asInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return (int) Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static double round4(double value) {
    return Math.round(value * 10_000.0d) / 10_000.0d;
  }
}
