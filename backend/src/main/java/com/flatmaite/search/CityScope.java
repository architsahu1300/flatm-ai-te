package com.flatmaite.search;

/**
 * The city a locality resolution is confined to. {@code UNSET} is not a Mumbai default in
 * disguise — it is a state callers branch on: resolution runs across every seeded city (today,
 * only Mumbai), and the caller is expected to tell the user why (spec §4.11). Lives in
 * {@code com.flatmaite.search} rather than a geocoding-specific subpackage because the whole
 * resolution ladder — gazetteer, own-data, and eventually geocoding — takes this same scope.
 */
public record CityScope(String city, Source source) {

  public enum Source {
    PROFILE,
    UNSET
  }

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
