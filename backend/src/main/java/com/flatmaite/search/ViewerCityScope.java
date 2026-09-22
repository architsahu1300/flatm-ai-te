package com.flatmaite.search;

import com.flatmaite.user.ProfileRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The city a viewer's searches are confined to, read from their profile locality (spec §4.10
 * point 2, §4.11). There are exactly two outcomes and the second is a state, not a fallback:
 *
 * <ul>
 *   <li>{@code PROFILE} — the viewer has a profile locality and we know which city it is in.
 *   <li>{@code UNSET} — anonymous, no profile, no profile locality, or a locality id the gazetteer
 *       cannot place. Resolution then runs across every seeded city (today that is only Mumbai) and
 *       the response says so, so the user can fix it. This is deliberately <b>not</b> a Mumbai
 *       default: a silent default would read correct today and be confidently wrong for the first
 *       Bangalore user, which is the bug class this workstream exists to remove.
 * </ul>
 *
 * <p>Anonymous search is first-class here — an anonymous searcher is {@code UNSET} and is served
 * results like anyone else, never asked to sign in first.
 */
@Component
@RequiredArgsConstructor
public class ViewerCityScope {

  private final ProfileRepository profiles;
  private final LocalityResolver localities;

  public CityScope forViewer(UUID userId) {
    if (userId == null) {
      return CityScope.unset();
    }
    List<UUID> localityIds = profiles.findCurrentLocalityId(userId);
    UUID localityId = localityIds.isEmpty() ? null : localityIds.get(0);
    if (localityId == null) {
      return CityScope.unset();
    }
    // cityOf is null for an id the gazetteer does not know, and CityScope.of(null) is unset — we
    // say we don't know the city rather than guessing one for them.
    return CityScope.of(localities.cityOf(localityId));
  }
}
