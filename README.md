# VitalEdge Java Client

Java client library for [VitalEdge](https://github.com/paegun/vitaledge), a graph database with a Cypher-compatible query interface over gRPC.

- `VitalEdgeClient` for synchronous gRPC access
- `execute`, `explain`, and `getCapabilities` methods
- thin result wrappers for rows, statistics, warnings, and capabilities
- runnable examples under `examples/BasicUsage.java`, `examples/IntermediateMovieRecommendation.java`, and `examples/AdvancedCyberThreatDetection.java`

## Requirements

- Java 17+
- Maven 3.9+
- A running VitalEdge server on gRPC port `7443`
- The VitalEdge proto checkout at `~/go/src/vitaledge/api/proto`

## Build

The Maven build generates gRPC stubs from the proto files in your Go checkout.

```bash
mvn compile
```

If your proto checkout lives somewhere else, override the default path:

```bash
mvn -Dvitaledge.proto.root=/path/to/vitaledge/api/proto compile
```

## Quick Example

```java
import com.vitaledge.client.VitalEdgeClient;

try (VitalEdgeClient client = VitalEdgeClient.builder()
    .host("localhost")
    .port(7443)
    .tenant("default")
    .build()) {
  var caps = client.getCapabilities();
  System.out.println(caps.protocolVersion());

  var result = client.execute(
      "MATCH (n) RETURN n LIMIT 5",
      null,
      null,
      false,
      true,
      false,
      null);
  System.out.println(result.columns());
  System.out.println(result.rows());

  var plan = client.explain("MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 10");
  System.out.println(plan.explainJson().toStringUtf8());
}
```

## Parameterized Query Example

The client mirrors the Python library’s simple Cypher parameter binding helper by rendering `$name` placeholders before sending the query.

```java
var query = """
MATCH (:Movie {title: $movieTitle})<-[r:ACTED_IN]-(p:Person)
WHERE r.role CONTAINS $actorRole
RETURN p.name AS actor, r.role AS role
""";

var parameters = java.util.Map.of(
    "movieTitle", "Wall Street",
    "actorRole", "Fox");

try (var client = VitalEdgeClient.builder().host("localhost").port(7443).build()) {
  var result = client.execute(query, parameters, null, false, true, false, null);
  for (var row : result.rows()) {
    System.out.println(row.get("actor") + " / " + row.get("role"));
  }
}
```

## Example Apps

### Basic Usage

See [examples/BasicUsage.java](examples/BasicUsage.java) for a runnable end-to-end sample.

Run with Maven:

```bash
mvn -Dvitaledge.proto.root=$HOME/go/src/vitaledge/api/proto \
  -Dexec.mainClass=examples.BasicUsage \
  exec:java
```

Or use the convenience script:

```bash
./examples/run_basic_usage.sh
```

If your proto root is not at the default location:

```bash
VITALEDGE_PROTO_ROOT=/path/to/vitaledge/api/proto ./examples/run_basic_usage.sh
```

### Intermediate Movie Recommendation

See [examples/IntermediateMovieRecommendation.java](examples/IntermediateMovieRecommendation.java) for a CSV-driven movie recommendation flow (ingest, scoring, and recommendations).

Required inputs:

- `--movies /path/to/movies.csv`
- `--ratings /path/to/ratings.csv`

Run with Maven:

```bash
mvn -Dvitaledge.proto.root=$HOME/go/src/vitaledge/api/proto \
  -Dexec.mainClass=examples.IntermediateMovieRecommendation \
  -Dexec.args="--movies /path/to/movies.csv --ratings /path/to/ratings.csv" \
  exec:java
```

Or use the convenience script:

```bash
./examples/run_intermediate_movie_recommendation.sh --movies /path/to/movies.csv --ratings /path/to/ratings.csv
```

You can also provide defaults via environment variables:

```bash
MOVIES_CSV=/path/to/movies.csv RATINGS_CSV=/path/to/ratings.csv ./examples/run_intermediate_movie_recommendation.sh
```

### Advanced Cyber Threat Detection

See [examples/AdvancedCyberThreatDetection.java](examples/AdvancedCyberThreatDetection.java) for a network-flow threat scoring and hunting workflow.

Required input:

- `--csv /path/to/network_flows.csv`

Run with Maven:

```bash
mvn -Dvitaledge.proto.root=$HOME/go/src/vitaledge/api/proto \
  -Dexec.mainClass=examples.AdvancedCyberThreatDetection \
  -Dexec.args="--csv /path/to/network_flows.csv" \
  exec:java
```

Or use the convenience script:

```bash
./examples/run_advanced_cyber_threat_detection.sh --csv /path/to/network_flows.csv
```

You can also provide the CSV via environment variable:

```bash
CYBER_FLOW_CSV=/path/to/network_flows.csv ./examples/run_advanced_cyber_threat_detection.sh
```

Why `java BasicUsage.java` fails:

- `java <file>.java` uses Java source-file mode and does not automatically include Maven project classes or dependencies.
- `BasicUsage` imports `com.vitaledge.client.*`, so it must run with the project classpath.

If you prefer the plain `java` launcher, build and run with an explicit classpath:

```bash
mvn -Dvitaledge.proto.root=$HOME/go/src/vitaledge/api/proto -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" examples.BasicUsage
```

## API

`VitalEdgeClient.builder()` supports the same core settings as the Python client:

- `host` defaults to `localhost`
- `port` defaults to `7443`
- `tenant` defaults to `default`
- `tls` enables TLS with system credentials unless `tlsCredentials` is provided
- `channelCustomizer` lets you tune gRPC channel builder settings

## Notes

- The client currently targets the `vitaledge.v1.QueryService` RPCs defined in `api/proto/vitaledge/v1/query.proto`.
- If you add new proto services later, extend the Java client in the same style instead of exposing raw stubs directly.