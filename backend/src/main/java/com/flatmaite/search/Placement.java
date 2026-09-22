package com.flatmaite.search;

import java.util.List;
import java.util.UUID;

/**
 * How a place name in a query was turned into somewhere on the map. {@code source} is what the
 * caller branches on: a gazetteer hit is a statement, own-data and geocoded hits are inferences,
 * and NONE means the name could not be placed at all — which the user is told about rather than
 * being silently served the whole city.
 */
public record Placement(
    List<UUID> localityIds, Double lat, Double lng, Source source, double confidence) {

  public enum Source {
    GAZETTEER,
    OWN_DATA,
    GEOCODED,
    NONE
  }

  public static Placement none() {
    return new Placement(List.of(), null, null, Source.NONE, 0.0);
  }

  public boolean placed() {
    return source != Source.NONE;
  }
}
