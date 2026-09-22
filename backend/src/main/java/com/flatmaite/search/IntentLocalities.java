package com.flatmaite.search;

import com.flatmaite.search.SearchIntent.CommuteTo;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Binds the names an intent carries (from the LLM, the parser or a chip edit) to locality ids, by
 * walking the full resolution ladder — gazetteer, then our own listing inventory — inside the
 * viewer's city scope (spec §4.3). An ambiguous alias expands to one ref per locality; a name no
 * layer can place moves to {@code unresolvedLocations} and stays in {@code freeText} so lexical and
 * semantic retrieval still see it. Never guesses by substring.
 *
 * <p>This is the only production caller of {@link LocalityResolver#resolve(String, CityScope)}, so
 * it is also where the ladder's verdict is kept rather than thrown away. A binding the gazetteer
 * made is a statement and is graded elsewhere, by the words that produced it. A binding our own
 * inventory made is an <em>inference</em>, and it arrives carrying the ladder's fixed 0.5: that
 * grade is written onto the slot it bound, so {@link ConfidenceGate} ranks by the place instead of
 * filtering on it (spec §4.3), and so the ids are never mistaken downstream for curated gazetteer
 * rows at full confidence.
 */
public final class IntentLocalities {

  private IntentLocalities() {}

  public static SearchIntent resolve(SearchIntent intent, LocalityResolver resolver, CityScope scope) {
    if (intent.locations() == null
        && intent.excludeLocations() == null
        && intent.commuteTo() == null
        && intent.unresolvedLocations() == null) {
      return intent;
    }
    List<String> unresolved =
        new ArrayList<>(intent.unresolvedLocations() == null ? List.of() : intent.unresolvedLocations());
    // slot -> the weakest grade any placement bound into it earned; only inferences land here
    Map<String, Double> inferred = new LinkedHashMap<>();
    List<LocationRef> home = resolveRefs(intent.locations(), resolver, scope, unresolved, inferred, "locations");
    List<LocationRef> exclude =
        resolveRefs(intent.excludeLocations(), resolver, scope, unresolved, inferred, "excludeLocations");

    // exclusion wins: drop a requested ref once its resolved id is also excluded. If nothing
    // requested remains the search runs city-wide minus the exclusions — no sentinel needed.
    Set<UUID> excludedIds = new HashSet<>();
    for (LocationRef ref : exclude) {
      excludedIds.add(ref.localityId());
    }
    home = home.stream().filter(ref -> !excludedIds.contains(ref.localityId())).toList();

    CommuteTo commute = intent.commuteTo();
    if (commute != null && commute.localityId() == null) {
      Placement placement = resolver.resolve(commute.place(), scope);
      if (placement.placed()) {
        UUID anchor = placement.localityIds().get(0);
        commute = new CommuteTo(nameOf(resolver, anchor, commute.place()), anchor, commute.maxMinutes());
        grade(inferred, "commuteTo", placement);
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
        .confidence(lowered(intent.confidence(), inferred))
        .freeText(freeText)
        .build();
  }

  private static List<LocationRef> resolveRefs(
      List<LocationRef> refs,
      LocalityResolver resolver,
      CityScope scope,
      List<String> unresolved,
      Map<String, Double> inferred,
      String slot) {
    List<LocationRef> out = new ArrayList<>();
    if (refs == null) {
      return out;
    }
    for (LocationRef ref : refs) {
      if (ref.localityId() != null) {
        out.add(ref);
        continue;
      }
      Placement placement = resolver.resolve(ref.name(), scope);
      if (!bindable(placement, slot)) {
        if (ref.name() != null && !unresolved.contains(ref.name())) {
          unresolved.add(ref.name());
        }
        continue;
      }
      grade(inferred, slot, placement);
      for (UUID id : placement.localityIds()) {
        if (out.stream().noneMatch(r -> id.equals(r.localityId()))) {
          out.add(new LocationRef(nameOf(resolver, id, ref.name()), id));
        }
      }
    }
    return out;
  }

  /**
   * Whether a placement may bind this slot at all. Anything the gazetteer states may. An inference
   * may not bind a slot {@link ConfidenceGate#ALWAYS_HARD} refuses to soften — today that is
   * {@code excludeLocations}: the 0.5 it carries would be recorded and then ignored by the gate, so
   * a society name matched against our own inventory would silently delete every listing in the
   * suburb around it. That is the precise harm confidence grading exists to prevent, and there is
   * no way to express the doubt on a slot that is always a filter. Such a name stays honestly
   * unplaced instead and is surfaced to the user (spec §4.8), which is what happens today.
   */
  private static boolean bindable(Placement placement, String slot) {
    if (!placement.placed()) {
      return false;
    }
    return placement.source() == Placement.Source.GAZETTEER || !ConfidenceGate.ALWAYS_HARD.contains(slot);
  }

  /**
   * Records the grade an <em>inferred</em> placement earns for the slot it bound. A gazetteer hit is
   * a statement and is left to {@link IntentGrounding}, which grades it against the user's own
   * words; overwriting that here would be a second, blunter opinion about the same thing.
   *
   * <p>The filter applies to the whole list at once, so the slot can only be as trustworthy as its
   * weakest member — the same rule {@code IntentGrounding.placeGrade} follows for a list mixing a
   * real locality with an invented one.
   */
  private static void grade(Map<String, Double> inferred, String slot, Placement placement) {
    if (placement.source() == Placement.Source.GAZETTEER) {
      return;
    }
    inferred.merge(slot, placement.confidence(), Math::min);
  }

  /**
   * Folds the inferred grades into whatever the intent already carried. Resolution may only lower a
   * grade, never raise one: an absent key reads 1.0 ({@link SearchIntent#confidenceOf}), so a slot
   * nothing inferred is left exactly as it was, and a slot an inference bound drops to the ladder's
   * figure for it.
   */
  private static Map<String, Double> lowered(Map<String, Double> existing, Map<String, Double> inferred) {
    if (inferred.isEmpty()) {
      return existing;
    }
    Map<String, Double> merged = new LinkedHashMap<>();
    if (existing != null) {
      merged.putAll(existing);
    }
    for (Map.Entry<String, Double> e : inferred.entrySet()) {
      Double current = merged.get(e.getKey());
      merged.put(e.getKey(), current == null ? e.getValue() : Math.min(current, e.getValue()));
    }
    return merged;
  }

  /**
   * The locality's canonical name, falling back to what the user actually called the place when the
   * resolver cannot name the id — never a city standing in for an unknown locality. An own-data hit
   * resolves to a real locality, so in practice this names the suburb the society sits in.
   */
  private static String nameOf(LocalityResolver resolver, UUID id, String asUserWroteIt) {
    String canonical = resolver.nameOf(id);
    return canonical != null ? canonical : asUserWroteIt;
  }
}
