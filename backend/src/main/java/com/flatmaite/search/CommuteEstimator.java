package com.flatmaite.search;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Commute approximation: haversine × 1.4 Mumbai road-circuity ÷ 20 km/h effective speed + 8 min
 * overhead. Deliberately crude and always labeled an estimate; a Distance-Matrix provider can
 * replace this behind the same method.
 */
@Component
@RequiredArgsConstructor
public class CommuteEstimator {

  public static final String METHOD = "haversine_estimate";
  private static final double ROAD_CIRCUITY = 1.4;
  private static final double SPEED_KMPH = 20.0;
  private static final int OVERHEAD_MIN = 8;

  private final LocalityRepository localities;
  private final Map<UUID, double[]> centroids = new HashMap<>();

  @PostConstruct
  void loadCentroids() {
    for (Locality l : localities.findAll()) {
      centroids.put(l.getId(), new double[] {l.getLat(), l.getLng()});
    }
  }

  /** Minutes between two localities, or null if either is unknown. */
  public Integer minutesBetween(UUID fromLocality, UUID toLocality) {
    double[] a = centroids.get(fromLocality);
    double[] b = centroids.get(toLocality);
    if (a == null || b == null) {
      return null;
    }
    return minutes(a[0], a[1], b[0], b[1]);
  }

  public Integer minutesFromPoint(Double lat, Double lng, UUID toLocality) {
    double[] b = centroids.get(toLocality);
    if (lat == null || lng == null || b == null) {
      return null;
    }
    return minutes(lat, lng, b[0], b[1]);
  }

  public static int minutes(double lat1, double lng1, double lat2, double lng2) {
    double roadKm = haversineKm(lat1, lng1, lat2, lng2) * ROAD_CIRCUITY;
    return (int) Math.round(roadKm / SPEED_KMPH * 60 + OVERHEAD_MIN);
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
