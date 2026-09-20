# WS6 Locality Distance & Fallback Ladder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make distance a first-class quantity in search, expand the seeded gazetteer, and replace the automatic filter-dropping rescue ladder with one that relaxes only distance and a labelled +10% budget band.

**Architecture:** Kilometres become the core unit (`CommuteEstimator` already computes them and discards them); a `Placement` record carries how a location was resolved, scoped to a city that may be explicitly `UNSET`; a four-tier fallback ladder replaces `RescueLadder.rungs()`; and the counts the pipeline already computes become part of the API contract so the UI can group results honestly.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Data JPA, Flyway, Postgres 16 + pgvector (`cube`/`earthdistance` extensions), JUnit 5 + AssertJ + Testcontainers, Next.js 15 / React 19 / TypeScript / Tailwind v4.

**Spec:** `docs/superpowers/specs/2026-09-21-locality-distance-fallback-design.md` — read it before Task 1. The plan argues from the spec; where they disagree, the spec wins and the plan is wrong.

## Global Constraints

- **Flyway owns the schema.** `spring.jpa.hibernate.ddl-auto: none`. Every schema change is a new versioned migration; never edit `V1`/`V2`.
- **`spring.jpa.open-in-view: false`.** Any repository method whose entities feed `ListingAssembler` needs `@EntityGraph(attributePaths = {"images", "amenities"})` or it throws `LazyInitializationException`.
- **Run Maven in the FOREGROUND** with a long timeout. Never background `./mvnw verify` and poll its output file — the harness appends `[exited with code N]` and the poll never terminates. Never run two Maven builds against `backend/target/` concurrently.
- **Seed ids are UUIDv3 over a stable key** (`SeedRunner.uuid(String)`), so re-seeding upserts. Never switch to random ids.
- **The project must run with no API keys.** Mock providers are the default; nothing in this plan adds a network dependency.
- **Intent extraction is not touched.** WS3's `IntentGoldenTest` must stay green throughout; if it fails, something leaked.
- **Seed facts other tests rely on:** `Random(42)`, "wardrobe" only in `FULLY_FURNISHED` descriptions, "essentials" only in `SEMI_FURNISHED`, "flatmate" only in has-flat headlines (`i % 4 == 0`). Preserve all of these when changing counts.
- **Commit after every task**, with the test and implementation in the same commit.

---

### Task 1: Migration — geo extensions, index, and per-city uniqueness

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__geo.sql`
- Test: `backend/src/test/java/com/flatmaite/listing/GeoMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ll_to_earth(lat, lng)` is indexable on `properties`; `localities` is unique on `(city, name)` rather than `(name)`.

- [ ] **Step 1: Write the failing test**

```java
package com.flatmaite.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class GeoMigrationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void earthdistanceIsInstalledAndPropertiesAreIndexedByPoint() {
    List<String> extensions =
        jdbc.queryForList("SELECT extname FROM pg_extension", String.class);
    assertThat(extensions).contains("cube", "earthdistance");

    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'properties'", String.class);
    assertThat(indexes).contains("idx_properties_geo");
  }

  @Test
  void localityNamesAreUniquePerCityNotGlobally() {
    List<String> constraints =
        jdbc.queryForList(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'localities'::regclass AND contype = 'u'
            """,
            String.class);
    assertThat(constraints).contains("localities_city_name_key");
    assertThat(constraints).doesNotContain("localities_name_key");

    // the same name in two cities is now legal
    jdbc.update(
        """
        INSERT INTO localities (name, city, lat, lng) VALUES
          ('MG Road', 'Mumbai', 19.0, 72.8),
          ('MG Road', 'Bangalore', 12.97, 77.6)
        """);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM localities WHERE name = 'MG Road'", Integer.class);
    assertThat(count).isEqualTo(2);
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=GeoMigrationIntegrationTest`
Expected: FAIL — `cube`/`earthdistance` absent, `idx_properties_geo` missing, and the second insert violates `localities_name_key`.

- [ ] **Step 3: Write the migration**

```sql
-- V3__geo.sql — distance becomes a first-class quantity (WS6 §4.1, §4.10)

CREATE EXTENSION IF NOT EXISTS cube;
CREATE EXTENSION IF NOT EXISTS earthdistance;

-- radius filtering runs against per-property points, not locality centroids
CREATE INDEX idx_properties_geo ON properties USING gist (ll_to_earth(lat, lng));

-- a locality name is unique within its city: Bangalore and Pune both have an
-- Indiranagar, and Delhi NCR and Navi Mumbai both have a Sector 15.
ALTER TABLE localities DROP CONSTRAINT localities_name_key;
ALTER TABLE localities ADD CONSTRAINT localities_city_name_key UNIQUE (city, name);
```

- [ ] **Step 4: Run the test and the full suite**

Run: `cd backend && ./mvnw test -Dtest=GeoMigrationIntegrationTest`
Expected: PASS.

Then run `cd backend && ./mvnw verify` in the foreground. Existing tests must stay green — `ll_to_earth` requires non-null lat/lng only at query time, so the index tolerates the one seeded property with null coordinates.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__geo.sql backend/src/test/java/com/flatmaite/listing/GeoMigrationIntegrationTest.java
git commit -m "Index properties by point and scope locality names to their city"
```

---

### Task 2: Kilometres on `Nearby`, and per-city commute calibration

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/CommuteEstimator.java`
- Modify: `backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java`
- Test: `backend/src/test/java/com/flatmaite/search/CommuteEstimatorNearbyTest.java` (exists — extend it)

**Interfaces:**
- Consumes: Task 1's index (not required to compile).
- Produces:
  - `record Nearby(UUID localityId, double km, int minutes)`
  - `List<Nearby> nearestLocalities(UUID anchor, double maxKm, int limit)` — replaces the minute-bounded overload
  - `Double kmBetween(UUID from, UUID to)`
  - `FlatmaiteProperties.Geo.calibrationFor(String city)` → `Calibration(double roadCircuity, double speedKmph, int overheadMin)`

- [ ] **Step 1: Write the failing test**

Append to `CommuteEstimatorNearbyTest`:

```java
  @Test
  void nearbyCarriesTheKilometresTheMinutesWereDerivedFrom() {
    // Goregaon → Malad: ~2.4 km apart by centroid
    List<CommuteEstimator.Nearby> near = estimator.nearestLocalities(goregaonId, 5.0, 10);

    CommuteEstimator.Nearby malad =
        near.stream().filter(n -> n.localityId().equals(maladId)).findFirst().orElseThrow();

    assertThat(malad.km()).isBetween(2.0, 5.0);
    // the minute figure is the km figure run through the calibration, not an independent guess
    assertThat(malad.minutes())
        .isEqualTo((int) Math.round(malad.km() / 20.0 * 60 + 8));
  }

  @Test
  void theConversionTableIsPinnedSoTheOverheadCannotDriftUnnoticed() {
    // 2 km and 5 km through Mumbai's calibration: circuity is already inside km
    assertThat(CommuteEstimator.minutesForKm(2.0, mumbai())).isEqualTo(14);
    assertThat(CommuteEstimator.minutesForKm(5.0, mumbai())).isEqualTo(23);
  }

  @Test
  void anUnknownCityFallsBackToMumbaiRatherThanZero() {
    FlatmaiteProperties.Geo geo = new FlatmaiteProperties.Geo();
    assertThat(geo.calibrationFor("Atlantis")).isEqualTo(geo.calibrationFor("Mumbai"));
  }

  private static FlatmaiteProperties.Calibration mumbai() {
    return new FlatmaiteProperties.Geo().calibrationFor("Mumbai");
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=CommuteEstimatorNearbyTest`
Expected: FAIL — `Nearby.km()`, `minutesForKm`, and `FlatmaiteProperties.Geo` do not exist.

- [ ] **Step 3: Add the calibration to `FlatmaiteProperties`**

```java
  /** Per-city travel calibration. Mumbai's numbers are the default for any city not listed. */
  public record Calibration(double roadCircuity, double speedKmph, int overheadMin) {}

  @Data
  public static class Geo {
    private static final Calibration MUMBAI = new Calibration(1.4, 20.0, 8);

    /** city name (case-insensitive) → calibration; Mumbai's values when absent. */
    private Map<String, Calibration> calibration = new LinkedHashMap<>();

    public Calibration calibrationFor(String city) {
      if (city == null) {
        return MUMBAI;
      }
      return calibration.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(city))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(MUMBAI);
    }
  }
```

Add `private Geo geo = new Geo();` to `FlatmaiteProperties` alongside the existing `Search`.

- [ ] **Step 4: Rework `CommuteEstimator`**

Replace the static constants and the minute-bounded ring with kilometre-first methods. The centroid map gains the city so calibration can be looked up per anchor:

```java
  public record Nearby(UUID localityId, double km, int minutes) {}

  private record Centroid(double lat, double lng, String city) {}

  private volatile Map<UUID, Centroid> centroids = new HashMap<>();

  /** Road-distance kilometres between two localities, or null if either is unknown. */
  public Double kmBetween(UUID fromLocality, UUID toLocality) {
    Centroid a = centroids.get(fromLocality);
    Centroid b = centroids.get(toLocality);
    if (a == null || b == null) {
      return null;
    }
    return roadKm(a.lat(), a.lng(), b.lat(), b.lng(), props.getGeo().calibrationFor(a.city()));
  }

  public Integer minutesBetween(UUID fromLocality, UUID toLocality) {
    Double km = kmBetween(fromLocality, toLocality);
    if (km == null) {
      return null;
    }
    Centroid a = centroids.get(fromLocality);
    return minutesForKm(km, props.getGeo().calibrationFor(a.city()));
  }

  /** Localities within {@code maxKm} of the anchor, nearest first, excluding the anchor. */
  public List<Nearby> nearestLocalities(UUID anchor, double maxKm, int limit) {
    if (anchor == null || !centroids.containsKey(anchor)) {
      return List.of();
    }
    FlatmaiteProperties.Calibration cal =
        props.getGeo().calibrationFor(centroids.get(anchor).city());
    return centroids.keySet().stream()
        .filter(id -> !id.equals(anchor))
        .map(id -> new Nearby(id, kmBetween(anchor, id), 0))
        .filter(n -> n.km() != null && n.km() <= maxKm)
        .map(n -> new Nearby(n.localityId(), n.km(), minutesForKm(n.km(), cal)))
        .sorted(Comparator.comparingDouble(Nearby::km))
        .limit(limit)
        .toList();
  }

  public static double roadKm(
      double lat1, double lng1, double lat2, double lng2, FlatmaiteProperties.Calibration cal) {
    return haversineKm(lat1, lng1, lat2, lng2) * cal.roadCircuity();
  }

  public static int minutesForKm(double roadKm, FlatmaiteProperties.Calibration cal) {
    return (int) Math.round(roadKm / cal.speedKmph() * 60 + cal.overheadMin());
  }
```

Delete the `ROAD_CIRCUITY` / `SPEED_KMPH` / `OVERHEAD_MIN` constants and the old `minutes(...)` static. `loadCentroids()` now reads `l.getCity()` into the `Centroid`. `METHOD` stays `"haversine_estimate"` — the estimate's contract is unchanged.

- [ ] **Step 5: Fix every caller the compiler flags**

`HybridRetriever.admittedLocalityIds` and `SearchPipeline` both call `nearestLocalities(anchor, maxMinutes, limit)`. Convert their arguments to kilometres in Task 3; until then, pass `props.getSearch().getNearbyRadiusKm()` once that field exists. If Task 3 has not run yet, temporarily pass `5.0` and leave a `// Task 3 replaces this literal` comment — the value is identical to the final default, so no behaviour is smuggled in.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=CommuteEstimatorNearbyTest,LocationWideningIntegrationTest`
Expected: PASS. `LocationWideningIntegrationTest` covers the 25-minute widening; a ~5 km ring admits approximately the same localities, but if its assertions name specific localities, update them to the km ring and say so in the commit message.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/CommuteEstimator.java backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java backend/src/test/java/com/flatmaite/search/CommuteEstimatorNearbyTest.java
git commit -m "Return the kilometres the commute estimate was already computing"
```

---

### Task 3: Rings configured in kilometres

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java:61-70`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java` (ring call sites)
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` (ring call sites)
- Modify: `README.md` (environment table)
- Test: `backend/src/test/java/com/flatmaite/search/HybridRetrieverAdmissionTest.java` (exists — extend)

**Interfaces:**
- Consumes: Task 2's `nearestLocalities(UUID, double, int)`.
- Produces: `Search.getNearbyRadiusKm()` (5.0), `getCloseRadiusKm()` (2.0), `getEscalationRadiusKm()` (5.0), `getMinResults()` (6). The two minute-based getters no longer exist.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void aNamedLocalityAdmitsEverythingWithinTheKilometreRing() {
    SearchIntent intent = SearchIntent.builder().locations(List.of("Goregaon")).build();

    List<UUID> admitted = retriever.admittedLocalityIds(intent, 5.0);

    assertThat(admitted).contains(goregaonId, maladId);   // Malad is ~2.4 km away
    assertThat(admitted).doesNotContain(colabaId);        // Colaba is ~25 km away
  }

  @Test
  void aZeroRadiusAdmitsOnlyTheRequestedLocality() {
    SearchIntent intent = SearchIntent.builder().locations(List.of("Goregaon")).build();

    assertThat(retriever.admittedLocalityIds(intent, 0.0)).containsExactly(goregaonId);
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=HybridRetrieverAdmissionTest`
Expected: FAIL — `admittedLocalityIds(SearchIntent, double)` does not exist; the current overload takes `Integer` minutes.

- [ ] **Step 3: Change the properties**

```java
  @Data
  public static class Search {
    /** A named home locality also admits every locality within this many kilometres. */
    private double nearbyRadiusKm = 5.0;

    /** Inside this distance a row is labelled "very close" rather than given a figure. */
    private double closeRadiusKm = 2.0;

    /** The ring searched after the user explicitly raises their budget. */
    private double escalationRadiusKm = 5.0;

    /** Below this many listings, the fallback ladder tops the page up. */
    private int minResults = 6;
  }
```

Delete `nearbyRadiusMinutes` and `rescueRadiusMinutes` outright — keeping both units invites drift.

- [ ] **Step 4: Update `application.yml`**

```yaml
  search:
    nearby-radius-km: ${SEARCH_NEARBY_RADIUS_KM:5.0}
    close-radius-km: ${SEARCH_CLOSE_RADIUS_KM:2.0}
    escalation-radius-km: ${SEARCH_ESCALATION_RADIUS_KM:5.0}
    min-results: ${SEARCH_MIN_RESULTS:6}
```

Place it under the existing `flatmaite:` block alongside `ai:` and `storage:`.

- [ ] **Step 5: Change the ring signatures**

In `HybridRetriever`, `admittedLocalityIds(SearchIntent, Integer radiusMinutes)` becomes `admittedLocalityIds(SearchIntent, double radiusKm)`, and the boolean overload passes `props.getSearch().getNearbyRadiusKm()` when widening and `0.0` when not. Inside, `nearbyMinutes` becomes `nearbyKm` (`Map<UUID, Double>`, merged with `Math::min`, sorted ascending) and the commute-anchor ring converts its minute budget to kilometres through the anchor city's calibration:

```java
    Integer commuteMinutes = enforceableCommuteMinutes(intent);
    UUID anchor = commuteMinutes == null ? null : commuteAnchor(intent);
    if (anchor != null) {
      FlatmaiteProperties.Calibration cal = props.getGeo().calibrationFor(cityOf(anchor));
      double budgetKm = Math.max(0, (commuteMinutes - cal.overheadMin()) / 60.0 * cal.speedKmph());
      nearbyKm.merge(anchor, 0.0, Math::min);
      for (CommuteEstimator.Nearby n : commuteEstimator.nearestLocalities(anchor, budgetKm, Integer.MAX_VALUE)) {
        nearbyKm.merge(n.localityId(), n.km(), Math::min);
      }
    }
```

A stated commute cap stays in minutes because that is what the user said; only the internal ring is kilometres.

- [ ] **Step 6: Update the README environment table**

Replace the `SEARCH_NEARBY_RADIUS_MINUTES` and `SEARCH_RESCUE_RADIUS_MINUTES` rows:

```markdown
| `SEARCH_NEARBY_RADIUS_KM` | `5.0` | A named locality also admits every locality within this many km |
| `SEARCH_CLOSE_RADIUS_KM` | `2.0` | Inside this distance a result is labelled "very close" |
| `SEARCH_ESCALATION_RADIUS_KM` | `5.0` | The ring searched after the user raises their budget |
| `SEARCH_MIN_RESULTS` | `6` | Below this many homes, the fallback ladder tops the page up |
```

- [ ] **Step 7: Run the tests**

Run: `cd backend && ./mvnw verify` (foreground)
Expected: BUILD SUCCESS. `ThinResultRescueTest` and `LocationWideningIntegrationTest` are the likeliest to need assertion updates.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/flatmaite/common/config/FlatmaiteProperties.java backend/src/main/resources/application.yml backend/src/main/java/com/flatmaite/search/HybridRetriever.java backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/test/java/com/flatmaite/search/HybridRetrieverAdmissionTest.java README.md
git commit -m "Express search rings in kilometres instead of estimated minutes"
```

---

### Task 4: Expand the gazetteer to 59 localities

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/seed/SeedLocalities.java`
- Modify: `backend/src/main/java/com/flatmaite/seed/SeedRunner.java:124-127`
- Test: `backend/src/test/java/com/flatmaite/seed/SeedLocalitiesTest.java`

**Interfaces:**
- Consumes: Task 1's `UNIQUE (city, name)`.
- Produces: `SeedLocalities.Seed(String name, String city, double lat, double lng, String[] aliases, int rentBand)` — note the new second component; every existing call site must add `"Mumbai"`.

- [ ] **Step 1: Write the failing test**

Replace the count assertions in `SeedLocalitiesTest` and add the coverage ones:

```java
  @Test
  void theGazetteerCoversFiftyNineMumbaiLocalities() {
    assertThat(SeedLocalities.ALL).hasSize(59);
    assertThat(SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).distinct()).hasSize(59);
    assertThat(SeedLocalities.ALL).allSatisfy(s -> assertThat(s.city()).isEqualTo("Mumbai"));
  }

  @Test
  void theLocalitiesTheReportedBugNeeded() {
    List<String> names = SeedLocalities.ALL.stream().map(SeedLocalities.Seed::name).toList();
    assertThat(names).contains("Kandivali", "Borivali", "Dahisar", "Mira Road", "Versova");
  }

  @Test
  void everyCentroidIsInsideTheMumbaiMetropolitanRegion() {
    assertThat(SeedLocalities.ALL)
        .allSatisfy(
            s -> {
              assertThat(s.lat()).isBetween(18.85, 19.35);
              assertThat(s.lng()).isBetween(72.75, 73.15);
            });
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=SeedLocalitiesTest`
Expected: FAIL — `hasSize(59)` sees 38, and `Seed::city` does not exist.

- [ ] **Step 3: Add the `city` component and the 21 new entries**

Change the record to `public record Seed(String name, String city, double lat, double lng, String[] aliases, int rentBand) {}` and add `"Mumbai"` as the second argument to all 38 existing rows. Then append:

```java
          new Seed("Versova", "Mumbai", 19.1290, 72.8140, new String[] {"versova beach", "seven bungalows"}, 27000),
          new Seed("Oshiwara", "Mumbai", 19.1480, 72.8320, new String[] {"lokhandwala", "lokhandwala complex"}, 24000),
          new Seed("Dahisar", "Mumbai", 19.2500, 72.8600, new String[] {"dahisar east", "dahisar west"}, 14000),
          new Seed("Mira Road", "Mumbai", 19.2810, 72.8710, new String[] {"mira bhayandar"}, 12000),
          new Seed("Bhayandar", "Mumbai", 19.3020, 72.8510, new String[] {"bhayander"}, 12000),
          new Seed("Byculla", "Mumbai", 18.9760, 72.8330, new String[] {}, 24000),
          new Seed("Prabhadevi", "Mumbai", 19.0150, 72.8280, new String[] {"elphinstone"}, 35000),
          new Seed("Vidyavihar", "Mumbai", 19.0800, 72.8970, new String[] {}, 18000),
          new Seed("Kalyan", "Mumbai", 19.2350, 73.1300, new String[] {"kalyan west", "kalyan east"}, 11000),
          new Seed("Dombivli", "Mumbai", 19.2170, 73.0870, new String[] {"dombivali"}, 11000),
          new Seed("Churchgate", "Mumbai", 18.9350, 72.8270, new String[] {}, 42000),
          new Seed("Marine Lines", "Mumbai", 18.9450, 72.8230, new String[] {"marine drive"}, 40000),
          new Seed("Fort", "Mumbai", 18.9340, 72.8360, new String[] {"ballard estate"}, 38000),
          new Seed("Grant Road", "Mumbai", 18.9630, 72.8150, new String[] {}, 30000),
          new Seed("Tardeo", "Mumbai", 18.9700, 72.8100, new String[] {}, 36000),
          new Seed("Malabar Hill", "Mumbai", 18.9550, 72.7950, new String[] {"walkeshwar"}, 45000),
          new Seed("Nerul", "Mumbai", 19.0330, 73.0180, new String[] {"nerul east", "nerul west"}, 16000),
          new Seed("Belapur", "Mumbai", 19.0170, 73.0360, new String[] {"cbd belapur"}, 15000),
          new Seed("Ghansoli", "Mumbai", 19.1200, 72.9980, new String[] {}, 14000),
          new Seed("Panvel", "Mumbai", 18.9890, 73.1100, new String[] {"new panvel"}, 12000),
          new Seed("Andheri", "Mumbai", 19.1190, 72.8470, new String[] {}, 23000));
```

The final `Andheri` row is the bare name, distinct from the existing `Andheri East` / `Andheri West` rows whose aliases already include `"andheri"`; it gives the unqualified name a centroid of its own rather than an arbitrary side. Kalyan, Dombivli and Panvel sit outside the metro bounds asserted in Step 1 — widen that assertion to `18.85..19.35` lat and `72.75..73.15` lng as written, which already covers them.

- [ ] **Step 4: Scale the seed volume**

In `SeedRunner`, `LISTING_COUNT` 80 → 240 and `USER_COUNT` 54 → 120. `FLATMATE_COUNT` stays 35. Locality assignment is already round-robin (`locs.get(i % locs.size())`), so 240 listings over 59 localities gives every locality at least four. Do not change the assignment expression, the `Random(42)` seed, or the furnishing/headline invariants other tests depend on.

- [ ] **Step 5: Verify per-locality coverage against a real database**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

Then confirm every locality has stock, and that Kandivali specifically has rows both under and over ₹15,000 so the tiers in Task 7 have something to find:

```bash
docker exec flatmaite-db psql -U flatmaite -d flatmaite -c "SELECT l.name, count(*) FROM listings li JOIN properties p ON p.id = li.property_id JOIN localities l ON l.id = p.locality_id GROUP BY l.name HAVING count(*) < 3 ORDER BY 2;"
docker exec flatmaite-db psql -U flatmaite -d flatmaite -c "SELECT count(*) FILTER (WHERE li.rent_monthly <= 15000) AS under, count(*) FILTER (WHERE li.rent_monthly > 15000) AS over FROM listings li JOIN properties p ON p.id = li.property_id JOIN localities l ON l.id = p.locality_id WHERE l.name = 'Kandivali';"
```

The first query should return no rows. If the second returns `0` in either column, widen the spread inside `rentFor(rentBand, type, bhk)` so a band produces rents on both sides of its midpoint, and re-seed.

- [ ] **Step 6: Run the suite**

Run: `cd backend && ./mvnw verify` (foreground)
Expected: BUILD SUCCESS. `SearchPipelineIntegrationTest` and `HybridRetrieverIntegrationTest` assert against seeded data and may need count updates — update the numbers, never the invariants listed in Global Constraints.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/seed/SeedLocalities.java backend/src/main/java/com/flatmaite/seed/SeedRunner.java backend/src/test/java/com/flatmaite/seed/SeedLocalitiesTest.java
git commit -m "Seed 59 Mumbai localities, each with listings of its own"
```

---

### Task 5: `Placement`, city scope, and the explicit `UNSET` state

**Files:**
- Create: `backend/src/main/java/com/flatmaite/search/Placement.java`
- Create: `backend/src/main/java/com/flatmaite/search/CityScope.java`
- Modify: `backend/src/main/java/com/flatmaite/search/LocalityResolver.java`
- Test: `backend/src/test/java/com/flatmaite/search/LocalityResolverTest.java` (exists — extend)
- Test: `backend/src/test/java/com/flatmaite/search/CityScopeTest.java` (create)

**Interfaces:**
- Consumes: Task 4's seeded cities.
- Produces:
  - `record CityScope(String city, Source source)` with `enum Source { PROFILE, UNSET }` and factories `CityScope.of(String city)` / `CityScope.unset()`
  - `record Placement(List<UUID> localityIds, Double lat, Double lng, Placement.Source source, double confidence)` with `enum Source { GAZETTEER, OWN_DATA, GEOCODED, NONE }` and `Placement.none()`
  - `LocalityResolver.scan(String query, CityScope scope)` and `.resolve(String name, CityScope scope)`

- [ ] **Step 1: Write the failing tests**

```java
// CityScopeTest.java
class CityScopeTest {

  @Test
  void anAbsentCityIsAStateNotADefault() {
    CityScope unset = CityScope.unset();

    assertThat(unset.source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(unset.city()).isNull();
    assertThat(unset.isSet()).isFalse();
  }

  @Test
  void aProfileCityIsCarriedAsItself() {
    CityScope scope = CityScope.of("Mumbai");

    assertThat(scope.source()).isEqualTo(CityScope.Source.PROFILE);
    assertThat(scope.city()).isEqualTo("Mumbai");
  }

  @Test
  void aBlankCityIsUnsetRatherThanAnEmptyString() {
    assertThat(CityScope.of("  ").source()).isEqualTo(CityScope.Source.UNSET);
    assertThat(CityScope.of(null).source()).isEqualTo(CityScope.Source.UNSET);
  }
}
```

Append to `LocalityResolverTest` (seed a second city directly through the repository so no second city ships in `SeedLocalities`):

```java
  @Test
  void aScopedResolutionNeverCrossesACityBoundary() {
    localities.save(Locality.builder().name("MG Road").city("Bangalore").lat(12.97).lng(77.6).build());
    localities.save(Locality.builder().name("MG Road").city("Mumbai").lat(19.06).lng(72.83).build());
    resolver.reload();

    Placement mumbai = resolver.resolve("MG Road", CityScope.of("Mumbai"));

    assertThat(mumbai.source()).isEqualTo(Placement.Source.GAZETTEER);
    assertThat(mumbai.localityIds()).hasSize(1);
    assertThat(localities.findById(mumbai.localityIds().get(0)).orElseThrow().getCity())
        .isEqualTo("Mumbai");
  }

  @Test
  void fuzzyMatchingIsScopedToo() {
    localities.save(Locality.builder().name("Indiranagar").city("Bangalore").lat(12.97).lng(77.64).build());
    resolver.reload();

    // near-miss spelling, but the wrong city — must not resolve
    assertThat(resolver.resolve("indiranagr", CityScope.of("Mumbai")).source())
        .isEqualTo(Placement.Source.NONE);
  }

  @Test
  void anUnsetScopeStillResolvesAcrossEveryCity() {
    assertThat(resolver.resolve("Kandivali", CityScope.unset()).source())
        .isEqualTo(Placement.Source.GAZETTEER);
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./mvnw test -Dtest=CityScopeTest,LocalityResolverTest`
Expected: FAIL — `CityScope`, `Placement` and the scoped overloads do not exist.

- [ ] **Step 3: Write the two records**

```java
// CityScope.java
public record CityScope(String city, Source source) {
  public enum Source { PROFILE, UNSET }

  public static CityScope of(String city) {
    return city == null || city.isBlank() ? unset() : new CityScope(city, Source.PROFILE);
  }

  public static CityScope unset() {
    return new CityScope(null, Source.UNSET);
  }

  public boolean isSet() {
    return source == Source.PROFILE;
  }
}
```

```java
// Placement.java
/**
 * How a place name in a query was turned into somewhere on the map. {@code source} is what the
 * caller branches on: a gazetteer hit is a statement, own-data and geocoded hits are inferences,
 * and NONE means the name could not be placed at all — which the user is told about rather than
 * being silently served the whole city.
 */
public record Placement(
    List<UUID> localityIds, Double lat, Double lng, Source source, double confidence) {

  public enum Source { GAZETTEER, OWN_DATA, GEOCODED, NONE }

  public static Placement none() {
    return new Placement(List.of(), null, null, Source.NONE, 0.0);
  }

  public boolean placed() {
    return source != Source.NONE;
  }
}
```

- [ ] **Step 4: Scope the resolver**

`LocalityResolver.load()` already builds `byPhrase` and `nameById`. Add `cityById` (`Map<UUID, String>`) populated from `l.getCity()`, then filter candidate ids by scope before matching:

```java
  private List<UUID> inScope(List<UUID> ids, CityScope scope) {
    if (!scope.isSet()) {
      return ids; // UNSET reaches every city — today that is Mumbai (§4.11)
    }
    return ids.stream()
        .filter(id -> scope.city().equalsIgnoreCase(cityById.get(id)))
        .toList();
  }
```

Apply `inScope` to the exact/alias result **and** inside `bestFuzzy` before scoring, so a wrong-city candidate can never win on similarity. Keep the existing unscoped `scan(String)` / `resolve(String)` methods delegating to `CityScope.unset()` so no caller breaks in this task.

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=CityScopeTest,LocalityResolverTest,IntentLocalitiesTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/Placement.java backend/src/main/java/com/flatmaite/search/CityScope.java backend/src/main/java/com/flatmaite/search/LocalityResolver.java backend/src/test/java/com/flatmaite/search/CityScopeTest.java backend/src/test/java/com/flatmaite/search/LocalityResolverTest.java
git commit -m "Scope locality resolution to a city that may be explicitly unset"
```

---

### Task 6: Resolution ladder step 2 — place names from our own listings

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/LocalityResolver.java`
- Modify: `backend/src/main/java/com/flatmaite/listing/PropertyRepository.java`
- Test: `backend/src/test/java/com/flatmaite/search/LocalityResolverOwnDataTest.java` (create)

**Interfaces:**
- Consumes: Task 5's `Placement` and `CityScope`.
- Produces: a `GAZETTEER` miss now falls through to `OWN_DATA` before `NONE`. `PropertyRepository.findPlacementByPlaceName(String needle, String city)` returns `(localityId, lat, lng)` rows.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void aSocietyNameNobodyCuratedStillResolves() {
    // "Hiranandani" is an alias of Powai in the gazetteer; use a name that is not
    Property p = properties.save(
        Property.builder()
            .ownerId(ownerId).localityId(goregaonId)
            .addressLine("Flat 4, Oberoi Splendor, Jogeshwari East")
            .societyName("Oberoi Splendor")
            .lat(19.1400).lng(72.8600).propertyType(PropertyType.APARTMENT).bhk((short) 2)
            .build());
    resolver.reload();

    Placement placement = resolver.resolve("Oberoi Splendor", CityScope.of("Mumbai"));

    assertThat(placement.source()).isEqualTo(Placement.Source.OWN_DATA);
    assertThat(placement.localityIds()).containsExactly(goregaonId);
    assertThat(placement.lat()).isEqualTo(19.1400);
    assertThat(placement.confidence()).isEqualTo(0.5);
  }

  @Test
  void aNameInNeitherTheGazetteerNorOurListingsIsHonestlyUnplaced() {
    assertThat(resolver.resolve("Ulwe", CityScope.of("Mumbai")).source())
        .isEqualTo(Placement.Source.NONE);
  }

  @Test
  void ownDataMatchingIsCityScopedLikeTheGazetteer() {
    assertThat(resolver.resolve("Oberoi Splendor", CityScope.of("Bangalore")).source())
        .isEqualTo(Placement.Source.NONE);
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=LocalityResolverOwnDataTest`
Expected: FAIL — the resolver returns `NONE` for "Oberoi Splendor".

- [ ] **Step 3: Add the repository query**

```java
  @Query(
      value =
          """
          SELECT p.locality_id AS localityId, avg(p.lat) AS lat, avg(p.lng) AS lng
          FROM properties p
          JOIN localities l ON l.id = p.locality_id
          WHERE (:city IS NULL OR lower(l.city) = lower(:city))
            AND (lower(p.society_name) LIKE lower(concat('%', :needle, '%'))
                 OR lower(p.address_line) LIKE lower(concat('%', :needle, '%')))
          GROUP BY p.locality_id
          ORDER BY count(*) DESC
          LIMIT 3
          """,
      nativeQuery = true)
  List<PlacementRow> findPlacementByPlaceName(@Param("needle") String needle, @Param("city") String city);

  interface PlacementRow {
    UUID getLocalityId();
    Double getLat();
    Double getLng();
  }
```

- [ ] **Step 4: Wire it into the ladder**

In `resolve(String name, CityScope scope)`, after the gazetteer steps return no match:

```java
    // step 2 — our own inventory names places no curated list will ever cover
    if (name.length() >= MIN_OWN_DATA_LENGTH) {
      List<PropertyRepository.PlacementRow> rows =
          properties.findPlacementByPlaceName(name, scope.city());
      if (!rows.isEmpty()) {
        return new Placement(
            rows.stream().map(PropertyRepository.PlacementRow::getLocalityId).toList(),
            rows.get(0).getLat(),
            rows.get(0).getLng(),
            Placement.Source.OWN_DATA,
            0.5); // INFERRED — ConfidenceGate treats it as a preference, not a filter
      }
    }
    return Placement.none();
```

`MIN_OWN_DATA_LENGTH = 5`, matching the resolver's existing `MIN_FUZZY_LENGTH`, so short tokens cannot `LIKE`-match half the table.

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=LocalityResolverOwnDataTest,LocalityResolverTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/LocalityResolver.java backend/src/main/java/com/flatmaite/listing/PropertyRepository.java backend/src/test/java/com/flatmaite/search/LocalityResolverOwnDataTest.java
git commit -m "Resolve place names from our own listings when the gazetteer misses"
```

---

### Task 7: The four-tier fallback ladder

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/RescueLadder.java`
- Modify: `backend/src/main/java/com/flatmaite/search/HybridRetriever.java:320`
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java`
- Test: `backend/src/test/java/com/flatmaite/search/FallbackLadderTest.java` (create)
- Test: `backend/src/test/java/com/flatmaite/search/ThinResultRescueTest.java` (exists — rewrite expectations)

**Interfaces:**
- Consumes: Task 3's ring config, Task 5's `Placement`.
- Produces:
  - `enum SearchTier { EXACT, NEARBY, OVER_BUDGET }`
  - `RescueLadder.tiers(SearchIntent intent, Placement placement, FlatmaiteProperties.Search props)` → `List<Tier>`
  - `record Tier(SearchTier tier, SearchIntent intent, double radiusKm)`
  - `RescueLadder.without(SearchIntent, String)` is unchanged and still used by the relaxer path.

- [ ] **Step 1: Write the failing test**

```java
class FallbackLadderTest {

  private final FlatmaiteProperties.Search props = new FlatmaiteProperties.Search();

  @Test
  void theLadderWidensBeforeItEverTouchesTheBudget() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of("Kandivali")).budgetMax(15000).build();

    List<RescueLadder.Tier> tiers = RescueLadder.tiers(intent, kandivaliPlacement(), props);

    assertThat(tiers).extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT, SearchTier.NEARBY, SearchTier.OVER_BUDGET);
    assertThat(tiers.get(0).radiusKm()).isEqualTo(0.0);
    assertThat(tiers.get(1).radiusKm()).isEqualTo(5.0);
  }

  @Test
  void theUnderBudgetTiersEnforceTheBudgetExactly() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of("Kandivali")).budgetMax(15000).build();

    List<RescueLadder.Tier> tiers = RescueLadder.tiers(intent, kandivaliPlacement(), props);

    assertThat(tiers.get(0).intent().budgetMax()).isEqualTo(15000);
    assertThat(tiers.get(1).intent().budgetMax()).isEqualTo(15000);
    assertThat(tiers.get(2).intent().budgetMax()).isEqualTo(16500); // +10%, labelled
  }

  @Test
  void everyTierKeepsEveryOtherFilter() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of("Kandivali"))
            .budgetMax(15000)
            .roomType(RoomType.PRIVATE)
            .furnished(Furnishing.FULLY_FURNISHED)
            .build();

    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .allSatisfy(
            t -> {
              assertThat(t.intent().roomType()).isEqualTo(RoomType.PRIVATE);
              assertThat(t.intent().furnished()).isEqualTo(Furnishing.FULLY_FURNISHED);
            });
  }

  @Test
  void aQueryWithNoPlaceHasNoDistanceTiers() {
    SearchIntent intent = SearchIntent.builder().budgetMax(15000).build();

    assertThat(RescueLadder.tiers(intent, Placement.none(), props))
        .extracting(RescueLadder.Tier::tier)
        .containsExactly(SearchTier.EXACT, SearchTier.OVER_BUDGET);
  }

  @Test
  void noTierEverDropsASlotTheWayTheOldLadderDid() {
    SearchIntent intent =
        SearchIntent.builder()
            .locations(List.of("Kandivali"))
            .budgetMax(15000)
            .roomType(RoomType.PRIVATE)
            .build();

    // the old ladder's later rungs cleared slots outright; nothing here may
    assertThat(RescueLadder.tiers(intent, kandivaliPlacement(), props))
        .noneSatisfy(t -> assertThat(t.intent().roomType()).isNull());
  }

  private static Placement kandivaliPlacement() {
    return new Placement(List.of(UUID.randomUUID()), 19.2045, 72.8519, Placement.Source.GAZETTEER, 1.0);
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=FallbackLadderTest`
Expected: FAIL — `SearchTier` and `RescueLadder.tiers` do not exist.

- [ ] **Step 3: Replace `rungs()` with `tiers()`**

```java
  /** Which block of the page a result belongs to. */
  public enum SearchTier { EXACT, NEARBY, OVER_BUDGET }

  /** @param radiusKm how far out this tier reaches; 0 means the requested placement only. */
  public record Tier(SearchTier tier, SearchIntent intent, double radiusKm) {}

  /**
   * What to try, in order, when the requested placement comes back thin. Distance first, because
   * moving the map is the smallest thing to give up; then a labelled +10% budget band. Nothing
   * else is relaxed automatically — every other filter the reader extracted is enforced in every
   * tier, and giving one up is a thing the user does by clicking a relaxer.
   */
  public static List<Tier> tiers(
      SearchIntent intent, Placement placement, FlatmaiteProperties.Search props) {
    List<Tier> out = new ArrayList<>();
    out.add(new Tier(SearchTier.EXACT, intent, 0.0));
    if (placement.placed()) {
      out.add(new Tier(SearchTier.NEARBY, intent, props.getNearbyRadiusKm()));
    }
    if (intent.budgetMax() != null) {
      SearchIntent band = intent.toBuilder().budgetMax((int) (intent.budgetMax() * 1.1)).build();
      out.add(
          new Tier(
              SearchTier.OVER_BUDGET,
              band,
              placement.placed() ? props.getNearbyRadiusKm() : 0.0));
    }
    return out;
  }
```

Keep `without(SearchIntent, String)` exactly as it is — `computeRelaxers` still uses it.

- [ ] **Step 4: Take the headroom out of the base filter**

At `HybridRetriever:320`, `b.budgetMax(intent.budgetMax() * 1.1)` becomes:

```java
    if (ConfidenceGate.isHard(intent, "budgetMax")) {
      // exactly what the user said — the +10% band is its own tier now (WS6 §4.5)
      b.budgetMax(intent.budgetMax());
    }
```

- [ ] **Step 5: Walk tiers instead of rungs in `SearchPipeline`**

`walkLadder` keeps its shape — a tier that finds nothing new is skipped silently, the walk stops once `minResults` distinct listings are in hand, and an exhausted ladder returns what it found. Only the input changes, from `RescueLadder.rungs(intent, rescueRadiusMinutes)` to `RescueLadder.tiers(intent, placement, props.getSearch())`, and `rungOf` becomes `tierOf` (`Map<UUID, SearchTier>`).

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw verify` (foreground)
Expected: BUILD SUCCESS. `ThinResultRescueTest` asserts the old behaviour — that a thin page drops a filter — and must be rewritten to assert the new contract: a thin page widens and then shows a labelled over-budget band, and never returns a row violating a non-budget filter.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/RescueLadder.java backend/src/main/java/com/flatmaite/search/HybridRetriever.java backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/test/java/com/flatmaite/search/FallbackLadderTest.java backend/src/test/java/com/flatmaite/search/ThinResultRescueTest.java
git commit -m "Widen and label instead of quietly dropping the user's filters"
```

---

### Task 8: Counted choices, and budget escalation the user asked for

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java`
- Test: `backend/src/test/java/com/flatmaite/search/BudgetChoiceTest.java` (create)
- Test: `backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java` (exists — extend)

**Interfaces:**
- Consumes: Task 7's tiers.
- Produces:
  - `record Choice(String label, ChoiceAction action, int value, long count)`, `enum ChoiceAction { RAISE_BUDGET }`
  - `SearchPipeline.budgetChoice(SearchIntent, Placement)` → `Optional<Choice>`

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void theChoiceNamesTheCheapestPriceThatActuallyOpensListings() {
    // Kandivali has nothing at 15k, three listings from 17k
    SearchIntent intent =
        SearchIntent.builder().locations(List.of("Kandivali")).budgetMax(15000).build();

    Choice choice = pipeline.budgetChoice(intent, kandivali()).orElseThrow();

    assertThat(choice.action()).isEqualTo(ChoiceAction.RAISE_BUDGET);
    assertThat(choice.value()).isEqualTo(17000);
    assertThat(choice.count()).isEqualTo(3);
    assertThat(choice.label()).isEqualTo("Kandivali has 3 from ₹17,000");
  }

  @Test
  void noChoiceIsOfferedWhenRaisingTheBudgetWouldOpenNothing() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of("Kandivali")).budgetMax(500_000).build();

    assertThat(pipeline.budgetChoice(intent, kandivali())).isEmpty();
  }

  @Test
  void anAutoShownOverBudgetRowNeverRewritesTheUsersBudget() {
    SearchIntent intent =
        SearchIntent.builder().locations(List.of("Kandivali")).budgetMax(15000).build();

    AiSearchResponse response = pipeline.search(intent, null, "test", sessionId, null);

    // the page may contain OVER_BUDGET rows; the intent that produced it may not have moved
    assertThat(response.homes()).anySatisfy(r -> assertThat(r.tier()).isEqualTo(SearchTier.OVER_BUDGET));
    assertThat(response.intent().budgetMax()).isEqualTo(15000);
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=BudgetChoiceTest`
Expected: FAIL — `budgetChoice` does not exist.

- [ ] **Step 3: Implement the choice**

Reuse the existing `countFor` / `cheapestRentFor` helpers rather than writing new queries:

```java
  /**
   * The one compromise worth offering when a place has nothing in budget: what the cheapest
   * listing there actually costs, and how many open up at that price. Returns empty when raising
   * the budget changes nothing, because an offer that opens no doors is noise.
   */
  Optional<Choice> budgetChoice(SearchIntent intent, Placement placement) {
    if (intent.budgetMax() == null || !placement.placed()) {
      return Optional.empty();
    }
    SearchIntent uncapped = intent.toBuilder().budgetMax(null).build();
    Integer cheapest = cheapestRentFor(uncapped);
    if (cheapest == null || cheapest <= intent.budgetMax()) {
      return Optional.empty();
    }
    int suggested = (int) (Math.ceil(cheapest / 500.0) * 500);
    long count = countFor(intent.toBuilder().budgetMax(suggested).build());
    if (count == 0) {
      return Optional.empty();
    }
    String place = placementName(placement);
    return Optional.of(
        new Choice(
            "%s has %d from ₹%,d".formatted(place, count, suggested),
            ChoiceAction.RAISE_BUDGET,
            suggested,
            count));
  }
```

- [ ] **Step 4: Confirm `/apply` already carries the escalation**

`AiSearchController.apply` endorses only what the user changed and persists the intent on the session, which is exactly the required semantics — a clicked `RAISE_BUDGET` posts `{budgetMax: 17000}` and it sticks. Add the escalation radius where the re-run resolves its ring: when the applied patch raised `budgetMax`, the ring is `props.getSearch().getEscalationRadiusKm()` rather than `getNearbyRadiusKm()`. Both default to 5.0, so write the test before assuming they are interchangeable — they are separately configurable on purpose.

- [ ] **Step 5: Add the integration test**

```java
  @Test
  void raisingTheBudgetSticksForTheRestOfTheSession() {
    AiSearchResponse first = search("single sharing room in Kandivali under 15k");
    Choice raise = first.choices().stream()
        .filter(c -> c.action() == ChoiceAction.RAISE_BUDGET).findFirst().orElseThrow();

    AiSearchResponse applied = apply(first.sessionId(), raise);
    assertThat(applied.intent().budgetMax()).isEqualTo(raise.value());

    AiSearchResponse refined = refine(first.sessionId(), "only verified ones");
    assertThat(refined.intent().budgetMax()).isEqualTo(raise.value());
  }
```

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=BudgetChoiceTest,SearchPipelineIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/test/java/com/flatmaite/search/BudgetChoiceTest.java backend/src/test/java/com/flatmaite/search/SearchPipelineIntegrationTest.java
git commit -m "Offer a counted budget choice and change the budget only when it is taken"
```

---

### Task 9: Expose tiers, summary, choices and city scope through the API

**Files:**
- Modify: `backend/src/main/java/com/flatmaite/search/SearchDtos.java:20-42`
- Modify: `backend/src/main/java/com/flatmaite/search/SearchPipeline.java` (response assembly)
- Modify: `backend/src/main/java/com/flatmaite/search/AiSearchController.java:44-76`
- Test: `backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java` (exists — extend)

**Interfaces:**
- Consumes: Tasks 5, 7, 8.
- Produces the response contract the frontend consumes in Task 10:
  - `AiResult` gains `SearchTier tier, Double distanceKm, Integer minutesFromAnchor, String anchorName`
  - `record ResultSummary(String anchorName, int exactCount, int nearbyCount, int overBudgetCount, String headline, String terminus)`
  - `record CitySearch(String city, CityScope.Source source, String prompt)`
  - `AiSearchResponse` gains `ResultSummary resultSummary, List<Choice> choices, CitySearch citySearch`

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void aThinLocalityIsReportedAsSuchRatherThanQuietlyGoingCitywide() throws Exception {
    mvc.perform(post("/api/v1/ai/search").contentType(APPLICATION_JSON)
            .content("{\"query\":\"single sharing room in Kandivali under 15k\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultSummary.anchorName").value("Kandivali"))
        .andExpect(jsonPath("$.data.resultSummary.headline").exists())
        .andExpect(jsonPath("$.data.homes[0].tier").exists())
        .andExpect(jsonPath("$.data.homes[0].distanceKm").exists());
  }

  @Test
  void aUserWithNoProfileLocalityIsToldTheCityIsUnknown() throws Exception {
    mvc.perform(post("/api/v1/ai/search").contentType(APPLICATION_JSON)
            .content("{\"query\":\"private room under 20k\"}"))
        .andExpect(jsonPath("$.data.citySearch.source").value("UNSET"))
        .andExpect(jsonPath("$.data.citySearch.city").doesNotExist())
        .andExpect(jsonPath("$.data.citySearch.prompt").exists());
  }

  @Test
  void aQueryNamingNoLocalityGetsNoFallbackFraming() throws Exception {
    mvc.perform(post("/api/v1/ai/search").contentType(APPLICATION_JSON)
            .content("{\"query\":\"private room under 20k\"}"))
        .andExpect(jsonPath("$.data.resultSummary.headline").doesNotExist())
        .andExpect(jsonPath("$.data.resultSummary.anchorName").doesNotExist());
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./mvnw test -Dtest=AiSearchControllerTest`
Expected: FAIL — none of those JSON paths exist.

- [ ] **Step 3: Extend the DTOs**

```java
  public record AiResult(
      String kind,
      int matchScore,
      List<MatchScorer.Component> scoreBreakdown,
      List<String> matchReasons,
      List<String> concerns,
      Integer commuteMinutes,
      String commuteLabel,
      ListingDtos.CardResponse home,
      FlatmateDtos.CardResponse flatmate,
      boolean nearMiss,
      String nearMissReason,
      RescueLadder.SearchTier tier,
      Double distanceKm,
      Integer minutesFromAnchor,
      String anchorName) {}

  public record ResultSummary(
      String anchorName,
      int exactCount,
      int nearbyCount,
      int overBudgetCount,
      String headline,
      String terminus) {}

  public record CitySearch(String city, CityScope.Source source, String prompt) {}

  public record AiSearchResponse(
      UUID sessionId,
      SearchIntent intent,
      String providerMode,
      List<AiResult> homes,
      List<AiResult> flatmates,
      List<Relaxer> relaxers,
      String note,
      ResultSummary resultSummary,
      List<Choice> choices,
      CitySearch citySearch) {}
```

- [ ] **Step 4: Assemble the summary copy**

```java
  private static final String CITY_PROMPT =
      "We don't know which city you're in. Set your location on your profile so we can show homes near you.";

  private ResultSummary summarize(
      String anchorName, SearchIntent intent, int exact, int nearby, int overBudget, boolean exhausted) {
    if (anchorName == null) {
      return new ResultSummary(null, exact, nearby, overBudget, null, null);
    }
    String budget = intent.budgetMax() == null ? null : "₹%,d".formatted(intent.budgetMax());
    String headline =
        exact == 0
            ? "No listings in %s%s.".formatted(anchorName, budget == null ? "" : " under " + budget)
            : exact < 3
                ? "Only %d listing%s in %s%s."
                    .formatted(exact, exact == 1 ? "" : "s", anchorName, budget == null ? "" : " under " + budget)
                : null;
    String terminus =
        exhausted && budget != null
            ? "No more listings within %s near %s.".formatted(budget, anchorName)
            : null;
    return new ResultSummary(anchorName, exact, nearby, overBudget, headline, terminus);
  }
```

The controller derives `CityScope` from the viewer's profile locality — `CityScope.of(profile.getCurrentLocality().getCity())` when present, `CityScope.unset()` otherwise, including for anonymous searchers — and passes it into `pipeline.search(...)`. `CitySearch.prompt` is `CITY_PROMPT` when the scope is `UNSET` and null otherwise.

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./mvnw verify` (foreground)
Expected: BUILD SUCCESS. Every `new AiResult(...)` call site needs the four new components; the compiler will list them all.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flatmaite/search/SearchDtos.java backend/src/main/java/com/flatmaite/search/SearchPipeline.java backend/src/main/java/com/flatmaite/search/AiSearchController.java backend/src/test/java/com/flatmaite/search/AiSearchControllerTest.java
git commit -m "Tell the client which tier each result came from and why"
```

---

### Task 10: Group the results in the UI

**Files:**
- Modify: `frontend/src/lib/ai-client.ts:36,60-61`
- Modify: `frontend/src/app/(app)/search/search-screen.tsx:199`
- Modify: `frontend/src/components/search/AiMatchCard.tsx:129-131`
- Create: `frontend/src/components/search/ResultGroups.tsx`
- Create: `frontend/src/components/search/CityScopePrompt.tsx`

**Interfaces:**
- Consumes: Task 9's response contract verbatim.
- Produces: no backend-visible interface.

- [ ] **Step 1: Mirror the new fields in the client types**

```ts
export type SearchTier = "EXACT" | "NEARBY" | "OVER_BUDGET";

export interface AiResult {
  // ...existing fields...
  tier?: SearchTier | null;
  distanceKm?: number | null;
  minutesFromAnchor?: number | null;
  anchorName?: string | null;
}

export interface ResultSummary {
  anchorName?: string | null;
  exactCount: number;
  nearbyCount: number;
  overBudgetCount: number;
  headline?: string | null;
  terminus?: string | null;
}

export interface CitySearch {
  city?: string | null;
  source: "PROFILE" | "UNSET";
  prompt?: string | null;
}

export interface Choice {
  label: string;
  action: "RAISE_BUDGET";
  value: number;
  count: number;
}
```

Add `resultSummary`, `choices` and `citySearch` to the response interface. Every field is optional on the client so a stale backend cannot blank the page.

- [ ] **Step 2: Write the grouping component**

`ResultGroups.tsx` takes `results` and `summary` and renders, in order: the headline (when present), then each non-empty tier under its own subhead — `In {anchorName}`, `Within 5 km, under budget`, `In {anchorName}, slightly over budget` — then the terminus line. It renders a flat list with no subheads when `summary.anchorName` is null, which is the citywide case.

- [ ] **Step 3: Write the city prompt**

`CityScopePrompt.tsx` renders nothing when `source === "PROFILE"`. When `UNSET` it renders the prompt above the results as an informational banner — not an error — with a link to `/profile` and a dismiss control that persists in `sessionStorage` behind a try/catch, since private-mode access can throw.

- [ ] **Step 4: Add the distance chip**

In `AiMatchCard`, beside the existing near-miss badge: `"very close"` when `distanceKm <= 2`, otherwise `"{distanceKm.toFixed(1)} km from {anchorName}"`, and nothing at all when `distanceKm` is null. Never render a bare number without its anchor — a distance from nowhere is meaningless.

- [ ] **Step 5: Verify against the running app**

The dev server and backend must both be running. **Never run `npm run build` while the dev server is running** — they share `.next` and it corrupts; kill dev, `rm -rf .next`, then build.

```bash
cd frontend && npm run lint && npx tsc --noEmit
```

Then search "single sharing room in Kandivali under 15k" at http://localhost:3000 and confirm the headline, the grouped sections, the distance chips and the budget choice all render.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/ai-client.ts frontend/src/app/\(app\)/search/search-screen.tsx frontend/src/components/search/AiMatchCard.tsx frontend/src/components/search/ResultGroups.tsx frontend/src/components/search/CityScopePrompt.tsx
git commit -m "Group results by tier and say when a locality came up short"
```

---

### Task 11: Full verification

**Files:** none — this task only runs things.

- [ ] **Step 1: Re-seed from clean and run the whole backend suite**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
cd backend && ./mvnw verify
```

Run Maven in the foreground. Expected: BUILD SUCCESS with every test green, including `IntentGoldenTest` — intent extraction was never touched, so a failure there means something leaked and must be investigated before this ships.

- [ ] **Step 2: Run the frontend checks**

```bash
cd frontend && npm run lint && npx tsc --noEmit
```

- [ ] **Step 3: Confirm the reported bug is actually fixed**

With both servers running, search **"Single sharing room in Kandivali under 15K"** and verify: no `Couldn't place` chip; Kandivali rows appear first; anything from another locality carries a distance chip naming Kandivali; nothing over ₹15,000 appears outside the labelled over-budget group; and the budget choice, if offered, names a real count.

- [ ] **Step 4: Commit any fixes and report**

Report the verification output verbatim — the `BUILD SUCCESS` line with its test counts, and the lint/tsc results. Claims of completion without that output are worthless.

---

## Self-Review

**Spec coverage:** §4.1 → Tasks 1–3. §4.2 → Task 4. §4.3 → Tasks 5–6. §4.4 → Task 7. §4.5 → Task 7 Step 4 and Task 8's immutability test. §4.6 → Tasks 9–10. §4.7 → Task 8. §4.8 → Tasks 6, 9, 10. §4.9 → Task 2 (null coordinates), Task 9 (no anchor), Task 1 (migration fails loudly). §4.10 → Tasks 1, 2, 5. §4.11 → Tasks 5, 9, 10. §5 → tests throughout plus Task 11. §6 → task order. §7 → the file lists.

**Type consistency:** `SearchTier` is declared once in `RescueLadder` (Task 7) and referenced as `RescueLadder.SearchTier` from `SearchDtos` (Task 9) and as the string union `SearchTier` in TypeScript (Task 10). `Placement.Source` (`GAZETTEER | OWN_DATA | GEOCODED | NONE`) is distinct from `CityScope.Source` (`PROFILE | UNSET`); both are always written qualified. `nearestLocalities` takes `(UUID, double, int)` everywhere after Task 2.

**Known cross-task hazard:** Task 2 Step 5 leaves a `5.0` literal in `HybridRetriever` that Task 3 replaces with `props.getSearch().getNearbyRadiusKm()`. If Tasks 2 and 3 are executed by different agents, Task 3's implementer must grep for `// Task 3 replaces this literal` before starting.
