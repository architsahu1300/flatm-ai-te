package com.flatmaite.search;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Commute approximation: haversine kilometres between locality centroids, converted to minutes
 * through a per-city calibration (road-circuity multiplier, effective speed, fixed overhead —
 * Mumbai's numbers by default). Deliberately crude and always labeled an estimate; a
 * Distance-Matrix provider can replace this behind the same method.
 */
@Component
@RequiredArgsConstructor
public class CommuteEstimator {

  public static final String METHOD = "haversine_estimate";

  private final LocalityRepository localities;
  private final FlatmaiteProperties props;
  // volatile + rebuild-then-swap: a concurrent reload() must not race a nearestLocalities() call
  // iterating the old map in place.
  private volatile Map<UUID, Centroid> centroids = new HashMap<>();

  private record Centroid(double lat, double lng, String city) {}

  @PostConstruct
  void loadCentroids() {
    Map<UUID, Centroid> newCentroids = new HashMap<>();
    for (Locality l : localities.findAll()) {
      newCentroids.put(l.getId(), new Centroid(l.getLat(), l.getLng(), l.getCity()));
    }
    centroids = newCentroids;
  }

  /** Re-reads the centroids; the seed runner calls this after inserting localities. */
  public void reload() {
    loadCentroids();
  }

  /** Straight-line kilometres between two locality centroids, or null if either is unknown. */
  public Double kmBetween(UUID fromLocality, UUID toLocality) {
    Centroid a = centroids.get(fromLocality);
    Centroid b = centroids.get(toLocality);
    if (a == null || b == null) {
      return null;
    }
    return haversineKm(a.lat(), a.lng(), b.lat(), b.lng());
  }

  /** Minutes between two localities, or null if either is unknown. */
  public Integer minutesBetween(UUID fromLocality, UUID toLocality) {
    Double km = kmBetween(fromLocality, toLocality);
    if (km == null) {
      return null;
    }
    Centroid a = centroids.get(fromLocality);
    return minutesForKm(km, props.getGeo().calibrationFor(a.city()));
  }

  public Integer minutesFromPoint(Double lat, Double lng, UUID toLocality) {
    // one read of the volatile map, as everywhere else here: a reload between two reads could
    // otherwise hand this method a centroid on the first and nothing on the second
    Centroid b = centroids.get(toLocality);
    if (lat == null || lng == null || b == null) {
      return null;
    }
    double km = haversineKm(lat, lng, b.lat(), b.lng());
    return minutesForKm(km, props.getGeo().calibrationFor(b.city()));
  }

  /**
   * Straight-line kilometres from an exact point to a locality centroid, or null when the locality
   * is unknown or the point is missing. This is the figure the API states as {@code distanceKm}, so
   * it is deliberately the same arithmetic the minutes estimate is derived from — a row can never
   * report a distance and a travel time that disagree about where it is.
   */
  public Double kmFromPoint(Double lat, Double lng, UUID toLocality) {
    Centroid b = centroids.get(toLocality);
    if (lat == null || lng == null || b == null) {
      return null;
    }
    return haversineKm(lat, lng, b.lat(), b.lng());
  }

  /** A locality, its straight-line distance from the anchor, and the estimated travel time. */
  public record Nearby(UUID localityId, double km, int minutes) {}

  /**
   * Localities closest to {@code anchor}, nearest first, excluding the anchor itself and anything
   * beyond {@code maxKm} of straight-line distance. Used to offer "also look in X, ~3.1 km away"
   * instead of the blunt "search everywhere" when a locality has no matches.
   */
  public List<Nearby> nearestLocalities(UUID anchor, double maxKm, int limit) {
    if (anchor == null || !centroids.containsKey(anchor)) {
      return List.of();
    }
    FlatmaiteProperties.Calibration cal = props.getGeo().calibrationFor(centroids.get(anchor).city());
    record Candidate(UUID id, Double km) {}
    return centroids.keySet().stream()
        .filter(id -> !id.equals(anchor))
        .map(id -> new Candidate(id, kmBetween(anchor, id)))
        .filter(c -> c.km() != null && c.km() <= maxKm)
        .map(c -> new Nearby(c.id(), c.km(), minutesForKm(c.km(), cal)))
        .sorted(Comparator.comparingDouble(Nearby::km))
        .limit(limit)
        .toList();
  }

  /** Straight-line kilometres run through a city's road-circuity, speed and fixed overhead. */
  public static int minutesForKm(double km, FlatmaiteProperties.Calibration cal) {
    return (int) Math.round(km * cal.roadCircuity() / cal.speedKmph() * 60 + cal.overheadMin());
  }

  public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
    double r = 6371.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * r * Math.asin(Math.sqrt(a));
  }
}
