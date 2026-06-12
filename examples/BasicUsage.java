package examples;

import com.vitaledge.client.Capabilities;
import com.vitaledge.client.ExplainResult;
import com.vitaledge.client.QueryResult;
import com.vitaledge.client.VitalEdgeClient;
import java.util.Map;

public final class BasicUsage {
  private BasicUsage() {}

  public static void main(String[] args) {
    try (VitalEdgeClient client = VitalEdgeClient.builder()
        .host("localhost")
        .port(7443)
        .tenant("default")
        .build()) {
      Capabilities capabilities = client.getCapabilities();
      System.out.println("Protocol version: " + capabilities.protocolVersion());
      System.out.println("Prepared queries: " + capabilities.preparedQuerySupported());

      QueryResult result = client.execute(
          "MATCH (n) RETURN n LIMIT 5",
          null,
          null,
          false,
          true,
          false,
          null);
      System.out.println("Columns: " + result.columns());
      for (Map<String, Object> row : result.rows()) {
        System.out.println(row);
      }

      ExplainResult plan = client.explain("MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 10");
      System.out.println(plan.explainJson().toStringUtf8());

      QueryResult parameterized = client.execute(
          "MATCH (:Movie {title: $movieTitle})<-[r:ACTED_IN]-(p:Person) WHERE r.role CONTAINS $actorRole RETURN p.name AS actor, r.role AS role",
          Map.of("movieTitle", "Wall Street", "actorRole", "Fox"),
          null,
          false,
          true,
          false,
          null);
      for (Map<String, Object> row : parameterized.rows()) {
        System.out.println("actor: " + row.get("actor") + ", role: " + row.get("role"));
      }
    }
  }
}