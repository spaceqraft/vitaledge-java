package examples;

import com.vitaledge.client.CreatePropertyIndexResult;
import com.vitaledge.client.VitalEdgeClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IntermediateMovieRecommendation {
  private static final Pattern YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)\\s*$");

  private IntermediateMovieRecommendation() {}

  private record Config(
      Path movies,
      Path ratings,
      String host,
      int port,
      String tenant,
      int batchSize,
      int edgeBatchSize,
      int ratingsLimit,
      int userSample,
      int limit) {}

  private record MovieRecord(int movieId, String title, int year, List<String> genres) {}

  private record RatingRecord(int userId, int movieId, double rating, int ts) {}

  private record Recommendation(int movieId, String title, int year, double score) {}

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);

    System.out.println("Loading movies from " + cfg.movies() + " ...");
    List<MovieRecord> movies = loadMovies(cfg.movies());
    System.out.println("  " + movies.size() + " movies loaded");

    System.out.println("Loading ratings from " + cfg.ratings() + " ...");
    List<RatingRecord> ratings = loadRatings(cfg.ratings(), cfg.ratingsLimit());
    System.out.println("  " + ratings.size() + " ratings loaded");

    if (movies.isEmpty() || ratings.isEmpty()) {
      throw new IllegalStateException("No data found - check CSV paths and column names.");
    }

    try (VitalEdgeClient client = VitalEdgeClient.builder()
        .host(cfg.host())
        .port(cfg.port())
        .tenant(cfg.tenant())
        .build()) {
      System.out.println("Resetting graph ...");
      resetGraph(client);

      System.out.println("Ensuring ingest lookup indexes ...");
      ensureIngestIndexes(client);

      System.out.println("Ingesting movies, genres, users, and ratings ...");
      ingestGraph(client, movies, ratings, cfg.batchSize(), cfg.edgeBatchSize());

      System.out.println("Scoring movies (Bayesian weighted average) ...");
      scoreMovies(client, ratings, cfg.batchSize());

      System.out.println("Generating recommendations for top " + cfg.userSample() + " users ...");
      recommendForUsers(client, cfg.userSample(), cfg.limit());

      System.out.println("\nResults:");
      printTopOverall(client, cfg.limit());
      printTopPerGenre(client, cfg.limit());
      printTopRecentYear(client, cfg.limit());
      printUserRecommendations(client, cfg.limit(), 5);
    }
  }

  private static Config parseArgs(String[] args) {
    Map<String, String> values = parseFlags(args);

    String movies = values.get("movies");
    String ratings = values.get("ratings");
    if (movies == null || movies.isBlank() || ratings == null || ratings.isBlank()) {
      throw new IllegalArgumentException("Missing required arguments --movies and --ratings");
    }

    return new Config(
        Path.of(movies),
        Path.of(ratings),
        values.getOrDefault("host", "localhost"),
        parseInt(values.getOrDefault("port", "7443"), 7443),
        values.getOrDefault("tenant", "movierec"),
        Math.max(1, parseInt(values.getOrDefault("batch-size", "500"), 500)),
        Math.max(1, parseInt(values.getOrDefault("edge-batch-size", "5000"), 5000)),
        Math.max(0, parseInt(values.getOrDefault("ratings-limit", "0"), 0)),
        Math.max(1, parseInt(values.getOrDefault("user-sample", "50"), 50)),
        Math.max(1, parseInt(values.getOrDefault("limit", "10"), 10)));
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

  private static List<MovieRecord> loadMovies(Path path) throws IOException {
    List<MovieRecord> movies = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String header = reader.readLine();
      if (header == null) {
        return movies;
      }
      List<String> headers = parseCsvLine(header);
      Map<String, Integer> index = toIndex(headers);

      String rowLine;
      while ((rowLine = reader.readLine()) != null) {
        List<String> row = parseCsvLine(rowLine);
        int movieId = parseInt(get(row, index, "movieId", get(row, index, "movie_id", "0")), 0);
        if (movieId == 0) {
          continue;
        }

        String rawTitle = get(row, index, "title", "").trim();
        ParsedTitle parsed = parseYear(rawTitle);

        String genresRaw = get(row, index, "genres", "");
        List<String> genres = new ArrayList<>();
        if (!genresRaw.isBlank()) {
          for (String g : genresRaw.split("\\|")) {
            String genre = g.trim();
            if (!genre.isEmpty() && !"(no genres listed)".equalsIgnoreCase(genre)) {
              genres.add(genre);
            }
          }
        }

        movies.add(new MovieRecord(movieId, parsed.title(), parsed.year(), genres));
      }
    }
    return movies;
  }

  private static List<RatingRecord> loadRatings(Path path, int limit) throws IOException {
    List<RatingRecord> ratings = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String header = reader.readLine();
      if (header == null) {
        return ratings;
      }
      List<String> headers = parseCsvLine(header);
      Map<String, Integer> index = toIndex(headers);

      String rowLine;
      while ((rowLine = reader.readLine()) != null) {
        List<String> row = parseCsvLine(rowLine);
        int userId = parseInt(get(row, index, "userId", get(row, index, "user_id", "0")), 0);
        int movieId = parseInt(get(row, index, "movieId", get(row, index, "movie_id", "0")), 0);
        if (userId == 0 || movieId == 0) {
          continue;
        }
        double rating = parseDouble(get(row, index, "rating", "0"), 0.0);
        int ts = parseInt(get(row, index, "timestamp", get(row, index, "ts", "0")), 0);
        ratings.add(new RatingRecord(userId, movieId, rating, ts));

        if (limit > 0 && ratings.size() >= limit) {
          break;
        }
      }
    }
    return ratings;
  }

  private static void resetGraph(VitalEdgeClient client) {
    client.execute("MATCH (n:Movie|Genre|User) DETACH DELETE n");
  }

  private static void ensureIngestIndexes(VitalEdgeClient client) {
    List<String[]> specs = List.of(
        new String[] {"Movie", "movie_id"},
        new String[] {"User", "user_id"},
        new String[] {"Genre", "genre"});

    for (String[] spec : specs) {
      String schema = spec[0];
      String property = spec[1];
      try {
        CreatePropertyIndexResult result = client.createPropertyIndex(schema, property, null, true, null);
        String state = result.created() ? "created" : "already exists";
        System.out.println(
            "  Index " + schema + "." + property + ": " + state
                + " (indexed_entities=" + result.indexedEntities() + ")");
      } catch (RuntimeException ex) {
        System.out.println("  Index " + schema + "." + property + ": failed (" + ex.getMessage() + ")");
      }
    }
  }

  private static void ingestGraph(
      VitalEdgeClient client,
      List<MovieRecord> movies,
      List<RatingRecord> ratings,
      int batchSize,
      int edgeBatchSize) {

    List<String> uniqueGenres = new ArrayList<>(
        new HashSet<>(movies.stream().flatMap(m -> m.genres().stream()).toList()));
    uniqueGenres.sort(String::compareTo);

    batchExecute(
        client,
        uniqueGenres,
        batchSize,
        genre -> Map.of("genre", genre),
        "genres",
        """
        UNWIND $genres AS g
        CREATE (:Genre {genre: g.genre})
        """);

    batchExecute(
        client,
        movies,
        batchSize,
        m -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("movie_id", m.movieId());
          map.put("title", m.title());
          map.put("year", m.year());
          return map;
        },
        "movies",
        """
        UNWIND $movies AS m
        CREATE (:Movie {movie_id: m.movie_id, title: m.title, year: m.year})
        """);

    List<Map<String, Object>> genrePairs = new ArrayList<>();
    for (MovieRecord movie : movies) {
      for (String genre : movie.genres()) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("movie_id", movie.movieId());
        pair.put("genre", genre);
        genrePairs.add(pair);
      }
    }

    batchExecute(
        client,
        genrePairs,
        edgeBatchSize,
        pair -> pair,
        "pairs",
        """
        UNWIND $pairs AS p
        MATCH (mov:Movie {movie_id: p.movie_id})
        MATCH (g:Genre {genre: p.genre})
        CREATE (mov)-[:GENRED]->(g)
        """);

    List<Integer> uniqueUsers = new ArrayList<>(new HashSet<>(ratings.stream().map(RatingRecord::userId).toList()));
    uniqueUsers.sort(Integer::compareTo);

    batchExecute(
        client,
        uniqueUsers,
        batchSize,
        id -> Map.of("user_id", id),
        "users",
        """
        UNWIND $users AS u
        CREATE (:User {user_id: u.user_id})
        """);

    batchExecute(
        client,
        ratings,
        batchSize,
        r -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("user_id", r.userId());
          map.put("movie_id", r.movieId());
          map.put("rating", r.rating());
          map.put("ts", r.ts());
          return map;
        },
        "ratings",
        """
        UNWIND $ratings AS r
        MATCH (u:User {user_id: r.user_id})
        MATCH (mov:Movie {movie_id: r.movie_id})
        CREATE (u)-[:RATED {rating: r.rating, ts: r.ts}]->(mov)
        """);
  }

  private static void scoreMovies(VitalEdgeClient client, List<RatingRecord> ratings, int batchSize) {
    if (ratings.isEmpty()) {
      return;
    }

    Map<Integer, Double> ratingSums = new HashMap<>();
    Map<Integer, Integer> ratingCounts = new HashMap<>();
    double totalSum = 0.0;
    int totalCount = 0;

    for (RatingRecord r : ratings) {
      ratingSums.merge(r.movieId(), r.rating(), Double::sum);
      ratingCounts.merge(r.movieId(), 1, Integer::sum);
      totalSum += r.rating();
      totalCount++;
    }

    double globalAvg = totalCount == 0 ? 3.0 : totalSum / totalCount;
    int c = 25;
    List<Map<String, Object>> updates = new ArrayList<>();

    for (Map.Entry<Integer, Integer> e : ratingCounts.entrySet()) {
      int movieId = e.getKey();
      int numRatings = e.getValue();
      double avgRating = ratingSums.get(movieId) / numRatings;
      double baseScore = (c * globalAvg + avgRating * numRatings) / (c + numRatings);

      Map<String, Object> update = new LinkedHashMap<>();
      update.put("movie_id", movieId);
      update.put("avg_rating", avgRating);
      update.put("num_ratings", numRatings);
      update.put("base_score", baseScore);
      updates.add(update);
    }

    batchExecute(
        client,
        updates,
        batchSize,
        u -> u,
        "updates",
        """
        UNWIND $updates AS u
        MATCH (m:Movie {movie_id: u.movie_id})
        SET m.avg_rating = u.avg_rating,
            m.num_ratings = u.num_ratings,
            m.base_score = u.base_score
        """);
  }

  private static void recommendForUsers(VitalEdgeClient client, int userSample, int limit) {
    List<Map<String, Object>> userRows = client.execute(
        """
        MATCH (u:User)-[:RATED]->()
        RETURN u.user_id AS user_id, count(*) AS rated_count
        ORDER BY rated_count DESC
        LIMIT $n
        """,
        Map.of("n", userSample),
        null,
        false,
        false,
        false,
        null).rows();

    int totalUsers = userRows.size();
    for (int i = 0; i < userRows.size(); i++) {
      Map<String, Object> row = userRows.get(i);
      int userId = asInt(row.get("user_id"));
      int ratedCount = asInt(row.get("rated_count"));
      System.out.println("  [" + (i + 1) + "/" + totalUsers + "] user_id=" + userId + " rated_count=" + ratedCount);

      Map<Integer, Double> decadeAffinity = getUserDecadeAffinities(client, userId);
      List<Map<String, Object>> candidates = getCollaborativeCandidates(client, userId, limit * 4);

      List<Recommendation> scored = new ArrayList<>();
      for (Map<String, Object> c : candidates) {
        int year = asInt(c.get("year"));
        int decade = year > 0 ? (year / 10) * 10 : 0;

        double collab = asDouble(c.get("peer_avg"))
            * Math.log(1.0 + asDouble(c.get("peer_count")))
            * asDouble(c.get("total_sim"));
        double decadeBoost = decade > 0 ? decadeAffinity.getOrDefault(decade, 0.0) * 0.5 : 0.0;
        double baseBoost = asDouble(c.get("base_score")) * 0.3;
        double score = round4(collab + decadeBoost + baseBoost);

        scored.add(new Recommendation(
            asInt(c.get("movie_id")),
            String.valueOf(c.getOrDefault("title", "")),
            year,
            score));
      }

      scored.sort(Comparator.comparingDouble(Recommendation::score).reversed());
      List<Recommendation> top = scored.subList(0, Math.min(limit, scored.size()));
      writeUserRecommendations(client, userId, top);
    }
  }

  private static Map<Integer, Double> getUserDecadeAffinities(VitalEdgeClient client, int userId) {
    List<Map<String, Object>> rows = client.execute(
        """
        MATCH (u:User {user_id: $user_id})-[r:RATED]->(m:Movie)
        WHERE m.year > 0
        RETURN m.year AS year, avg(r.rating) AS avg_rating
        """,
        Map.of("user_id", userId),
        null,
        false,
        false,
        false,
        null).rows();

    Map<Integer, Double> decadeSums = new HashMap<>();
    Map<Integer, Integer> decadeCounts = new HashMap<>();

    for (Map<String, Object> row : rows) {
      int year = asInt(row.get("year"));
      double avgRating = asDouble(row.get("avg_rating"));
      if (year <= 0) {
        continue;
      }
      int decade = (year / 10) * 10;
      decadeSums.merge(decade, avgRating, Double::sum);
      decadeCounts.merge(decade, 1, Integer::sum);
    }

    Map<Integer, Double> out = new HashMap<>();
    for (Map.Entry<Integer, Double> e : decadeSums.entrySet()) {
      int decade = e.getKey();
      out.put(decade, e.getValue() / decadeCounts.get(decade));
    }
    return out;
  }

  private static List<Map<String, Object>> getCollaborativeCandidates(
      VitalEdgeClient client,
      int userId,
      int candidateLimit) {
    return client.execute(
        """
        MATCH (target:User {user_id: $user_id})-[r1:RATED]->(shared:Movie)<-[r2:RATED]-(peer:User)
        WHERE peer <> target AND abs(r1.rating - r2.rating) <= 1.5
        WITH target, peer,
             count(shared) AS shared_count,
             avg(abs(r1.rating - r2.rating)) AS avg_diff
        WHERE shared_count >= 3
        WITH target, peer,
             shared_count * (1.0 / (1.0 + avg_diff)) AS similarity
        ORDER BY similarity DESC
        LIMIT 30
        MATCH (peer)-[rp:RATED]->(candidate:Movie)
        WHERE rp.rating >= 4.0 AND NOT (target)-[:RATED]->(candidate)
        RETURN candidate.movie_id AS movie_id,
               candidate.title    AS title,
               candidate.year     AS year,
               coalesce(candidate.base_score, 0.0) AS base_score,
               avg(rp.rating)     AS peer_avg,
               count(rp)          AS peer_count,
               sum(similarity)    AS total_sim
        ORDER BY total_sim DESC
        LIMIT $candidate_limit
        """,
        Map.of("user_id", userId, "candidate_limit", candidateLimit),
        null,
        false,
        false,
        false,
        null).rows();
  }

  private static void writeUserRecommendations(
      VitalEdgeClient client,
      int userId,
      List<Recommendation> recommendations) {
    if (recommendations.isEmpty()) {
      return;
    }

    List<Map<String, Object>> payload = new ArrayList<>();
    for (int i = 0; i < recommendations.size(); i++) {
      Recommendation rec = recommendations.get(i);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("user_id", userId);
      entry.put("movie_id", rec.movieId());
      entry.put("score", rec.score());
      entry.put("rank", i + 1);
      payload.add(entry);
    }

    client.execute(
        """
        UNWIND $recs AS r
        MATCH (u:User {user_id: r.user_id}), (m:Movie {movie_id: r.movie_id})
        CREATE (u)-[rec:RECOMMENDED]->(m)
        SET rec.score = r.score, rec.rank = r.rank
        """,
        Map.of("recs", payload),
        null,
        false,
        false,
        false,
        null);
  }

  private static void printTopOverall(VitalEdgeClient client, int limit) {
    List<Map<String, Object>> rows = client.execute(
        """
        MATCH (m:Movie)
        WHERE m.num_ratings >= 1
        RETURN m.title AS title, m.year AS year,
               m.avg_rating AS avg_rating, m.num_ratings AS num_ratings,
               m.base_score AS score
        ORDER BY score DESC
        LIMIT $limit
        """,
        Map.of("limit", limit),
        null,
        false,
        false,
        false,
        null).rows();
    printRows("Top " + limit + " Overall Movies (Bayesian score)", rows);
  }

  private static void printTopPerGenre(VitalEdgeClient client, int limit) {
    List<Map<String, Object>> genreRows = client.execute(
        "MATCH (g:Genre) RETURN g.genre AS genre ORDER BY g.genre").rows();

    for (Map<String, Object> g : genreRows) {
      String genre = String.valueOf(g.getOrDefault("genre", ""));
      if (genre.isBlank()) {
        continue;
      }
      List<Map<String, Object>> rows = client.execute(
          """
          MATCH (m:Movie)-[:GENRED]->(g:Genre {genre: $genre})
          WHERE m.num_ratings >= 1
          RETURN m.title AS title, m.year AS year, m.base_score AS score
          ORDER BY score DESC
          LIMIT $limit
          """,
          Map.of("genre", genre, "limit", limit),
          null,
          false,
          false,
          false,
          null).rows();
      printRows("Top " + limit + " " + genre + " Movies", rows);
    }
  }

  private static void printTopRecentYear(VitalEdgeClient client, int limit) {
    List<Map<String, Object>> yearRows = client.execute(
        "MATCH (m:Movie) WHERE m.year > 0 AND m.num_ratings >= 1 RETURN max(m.year) AS max_year").rows();

    if (yearRows.isEmpty() || yearRows.get(0).get("max_year") == null) {
      System.out.println("\n=== Top Recent Year Movies === (no year data available)");
      return;
    }

    int maxYear = asInt(yearRows.get(0).get("max_year"));
    List<Map<String, Object>> rows = client.execute(
        """
        MATCH (m:Movie)
        WHERE m.year = $year AND m.num_ratings >= 1
        RETURN m.title AS title, m.year AS year,
               m.avg_rating AS avg_rating, m.base_score AS score
        ORDER BY score DESC
        LIMIT $limit
        """,
        Map.of("year", maxYear, "limit", limit),
        null,
        false,
        false,
        false,
        null).rows();

    printRows("Top " + limit + " Movies from " + maxYear, rows);
  }

  private static void printUserRecommendations(VitalEdgeClient client, int limit, int displayCount) {
    List<Map<String, Object>> userRows = client.execute(
        """
        MATCH (u:User)-[rec:RECOMMENDED]->()
        RETURN u.user_id AS user_id, count(rec) AS rec_count
        ORDER BY rec_count DESC
        LIMIT $n
        """,
        Map.of("n", displayCount),
        null,
        false,
        false,
        false,
        null).rows();

    for (Map<String, Object> row : userRows) {
      int userId = asInt(row.get("user_id"));
      List<Map<String, Object>> recs = client.execute(
          """
          MATCH (u:User {user_id: $user_id})-[rec:RECOMMENDED]->(m:Movie)
          RETURN m.title AS title, m.year AS year,
                 rec.score AS score, rec.rank AS rank
          ORDER BY rank
          LIMIT $limit
          """,
          Map.of("user_id", userId, "limit", limit),
          null,
          false,
          false,
          false,
          null).rows();
      printRows("Top " + limit + " Recommendations for User " + userId, recs);
    }
  }

  private static <T> void batchExecute(
      VitalEdgeClient client,
      List<T> input,
      int batchSize,
      java.util.function.Function<T, Map<String, Object>> mapper,
      String parameterName,
      String query) {
    for (int start = 0; start < input.size(); start += batchSize) {
      int end = Math.min(start + batchSize, input.size());
      List<Map<String, Object>> batch = new ArrayList<>(end - start);
      for (int i = start; i < end; i++) {
        batch.add(mapper.apply(input.get(i)));
      }
      client.execute(query, Map.of(parameterName, batch), null, false, false, false, null);
    }
  }

  private static void printRows(String title, List<Map<String, Object>> rows) {
    System.out.println("\n=== " + title + " ===");
    if (rows.isEmpty()) {
      System.out.println("  (no results)");
      return;
    }
    for (Map<String, Object> row : rows) {
      System.out.println("  " + row);
    }
  }

  private static record ParsedTitle(String title, int year) {}

  private static ParsedTitle parseYear(String title) {
    Matcher m = YEAR_PATTERN.matcher(title);
    if (m.find()) {
      int year = parseInt(m.group(1), 0);
      String clean = title.substring(0, m.start()).trim();
      return new ParsedTitle(clean, year);
    }
    return new ParsedTitle(title.trim(), 0);
  }

  private static Map<String, Integer> toIndex(List<String> headers) {
    Map<String, Integer> idx = new HashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      idx.put(headers.get(i), i);
    }
    return idx;
  }

  private static String get(List<String> row, Map<String, Integer> idx, String column, String fallback) {
    Integer position = idx.get(column);
    if (position == null || position < 0 || position >= row.size()) {
      return fallback;
    }
    String value = row.get(position);
    return value == null ? fallback : value;
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

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception ex) {
      return fallback;
    }
  }

  private static double parseDouble(String value, double fallback) {
    try {
      return Double.parseDouble(value.trim());
    } catch (Exception ex) {
      return fallback;
    }
  }

  private static int asInt(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
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

  private static double asDouble(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value == null) {
      return 0.0;
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return 0.0;
    }
  }

  private static double round4(double value) {
    return Math.round(value * 10_000.0d) / 10_000.0d;
  }
}
