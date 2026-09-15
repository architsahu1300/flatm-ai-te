package com.flatmaite.search;

import com.flatmaite.search.LocalityResolver.Match;
import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Binds the names an intent carries (from the LLM, the parser or a chip edit) to locality ids.
 * An ambiguous alias expands to one ref per locality; a name no layer can place moves to
 * {@code unresolvedLocations} and stays in {@code freeText} so lexical and semantic retrieval
 * still see it. Never guesses by substring.
 */
public final class IntentLocalities {

  private IntentLocalities() {}

  public static SearchIntent resolve(SearchIntent intent, LocalityResolver resolver) {
    if (intent.locations() == null
        && intent.excludeLocations() == null
        && intent.commuteTo() == null
        && intent.unresolvedLocations() == null) {
      return intent;
    }
    List<String> unresolved =
        new ArrayList<>(intent.unresolvedLocations() == null ? List.of() : intent.unresolvedLocations());
    List<LocationRef> home = resolveRefs(intent.locations(), resolver, unresolved);
    List<LocationRef> exclude = resolveRefs(intent.excludeLocations(), resolver, unresolved);

    // exclusion wins: drop a requested ref once its resolved id is also excluded. If nothing
    // requested remains the search runs city-wide minus the exclusions — no sentinel needed.
    Set<UUID> excludedIds = new HashSet<>();
    for (LocationRef ref : exclude) {
      excludedIds.add(ref.localityId());
    }
    home = home.stream().filter(ref -> !excludedIds.contains(ref.localityId())).toList();

    CommuteTo commute = intent.commuteTo();
    if (commute != null && commute.localityId() == null) {
      Optional<Match> m = resolver.resolve(commute.place());
      if (m.isPresent()) {
        UUID anchor = m.get().localityIds().get(0);
        commute = new CommuteTo(resolver.nameOf(anchor), anchor, commute.maxMinutes());
      } else if (commute.place() != null && !unresolved.contains(commute.place())) {
        unresolved.add(commute.place());
      }
    }

    String freeText = intent.freeText();
    for (String name : unresolved) {
      if (freeText == null || !freeText.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
        freeText = SearchIntent.joinFreeText(freeText, name);
      }
    }

    return intent.toBuilder()
        .locations(home.isEmpty() ? null : home)
        .excludeLocations(exclude.isEmpty() ? null : exclude)
        .commuteTo(commute)
        .unresolvedLocations(unresolved.isEmpty() ? null : unresolved)
        .freeText(freeText)
        .build();
  }

  private static List<LocationRef> resolveRefs(List<LocationRef> refs, LocalityResolver resolver, List<String> unresolved) {
    List<LocationRef> out = new ArrayList<>();
    if (refs == null) {
      return out;
    }
    for (LocationRef ref : refs) {
      if (ref.localityId() != null) {
        out.add(ref);
        continue;
      }
      Optional<Match> m = resolver.resolve(ref.name());
      if (m.isEmpty()) {
        if (ref.name() != null && !unresolved.contains(ref.name())) {
          unresolved.add(ref.name());
        }
        continue;
      }
      for (UUID id : m.get().localityIds()) {
        if (out.stream().noneMatch(r -> id.equals(r.localityId()))) {
          out.add(new LocationRef(resolver.nameOf(id), id));
        }
      }
    }
    return out;
  }
}
