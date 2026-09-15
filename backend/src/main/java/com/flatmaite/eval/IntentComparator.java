package com.flatmaite.eval;

import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/** Slot-by-slot comparison of an expected and an actual intent. Never throws on nulls. */
public final class IntentComparator {

  public static final List<String> SLOTS =
      List.of("searchTarget", "locations", "excludeLocations", "unresolvedLocations", "commuteTo.place",
          "commuteTo.maxMinutes", "budgetMin", "budgetMax", "maxDeposit", "roomType", "listingTypes",
          "bhk.min", "bhk.max", "furnished", "genderPreference", "couplesOk", "verifiedOnly", "amenities",
          "lifestyle.smoking", "lifestyle.pets", "lifestyle.diet", "lifestyle.quiet");

  public record SlotResult(String slot, boolean match, String expected, String actual) {}

  private IntentComparator() {}

  /** @param actualNameOf canonical name of an actual ref (resolver lookup; falls back to the ref's name). */
  public static List<SlotResult> compare(SearchIntent expected, SearchIntent actual, Function<LocationRef, String> actualNameOf) {
    List<SlotResult> out = new ArrayList<>(SLOTS.size());
    out.add(scalar("searchTarget", expected.searchTarget(), actual.searchTarget()));
    out.add(set("locations", names(expected.locations(), LocationRef::name), names(actual.locations(), actualNameOf)));
    out.add(set("excludeLocations", names(expected.excludeLocations(), LocationRef::name), names(actual.excludeLocations(), actualNameOf)));
    out.add(set("unresolvedLocations", strings(expected.unresolvedLocations()), strings(actual.unresolvedLocations())));
    out.add(text("commuteTo.place",
        expected.commuteTo() == null ? null : expected.commuteTo().place(),
        actual.commuteTo() == null ? null : actual.commuteTo().place()));
    out.add(scalar("commuteTo.maxMinutes",
        expected.commuteTo() == null ? null : expected.commuteTo().maxMinutes(),
        actual.commuteTo() == null ? null : actual.commuteTo().maxMinutes()));
    out.add(scalar("budgetMin", expected.budgetMin(), actual.budgetMin()));
    out.add(scalar("budgetMax", expected.budgetMax(), actual.budgetMax()));
    out.add(scalar("maxDeposit", expected.maxDeposit(), actual.maxDeposit()));
    out.add(scalar("roomType", expected.roomType(), actual.roomType()));
    out.add(set("listingTypes", enums(expected.listingTypes()), enums(actual.listingTypes())));
    out.add(scalar("bhk.min", expected.bhk() == null ? null : expected.bhk().min(), actual.bhk() == null ? null : actual.bhk().min()));
    out.add(scalar("bhk.max", expected.bhk() == null ? null : expected.bhk().max(), actual.bhk() == null ? null : actual.bhk().max()));
    out.add(scalar("furnished", expected.furnished(), actual.furnished()));
    out.add(scalar("genderPreference", expected.genderPreference(), actual.genderPreference()));
    out.add(scalar("couplesOk", expected.couplesOk(), actual.couplesOk()));
    out.add(scalar("verifiedOnly", expected.verifiedOnly(), actual.verifiedOnly()));
    out.add(set("amenities", strings(expected.amenities()), strings(actual.amenities())));
    SearchIntent.Lifestyle el = expected.lifestyle();
    SearchIntent.Lifestyle al = actual.lifestyle();
    out.add(scalar("lifestyle.smoking", el == null ? null : el.smoking(), al == null ? null : al.smoking()));
    out.add(scalar("lifestyle.pets", el == null ? null : el.pets(), al == null ? null : al.pets()));
    out.add(scalar("lifestyle.diet", el == null ? null : el.diet(), al == null ? null : al.diet()));
    out.add(scalar("lifestyle.quiet", el == null ? null : el.quiet(), al == null ? null : al.quiet()));
    return out;
  }

  private static SlotResult scalar(String slot, Object expected, Object actual) {
    return new SlotResult(slot, Objects.equals(expected, actual), String.valueOf(expected), String.valueOf(actual));
  }

  private static SlotResult text(String slot, String expected, String actual) {
    return new SlotResult(slot, norm(expected).equals(norm(actual)), String.valueOf(expected), String.valueOf(actual));
  }

  private static SlotResult set(String slot, TreeSet<String> expected, TreeSet<String> actual) {
    return new SlotResult(slot, expected.equals(actual), String.join(", ", expected), String.join(", ", actual));
  }

  private static TreeSet<String> names(List<LocationRef> refs, Function<LocationRef, String> nameOf) {
    TreeSet<String> out = new TreeSet<>();
    if (refs != null) {
      for (LocationRef r : refs) {
        String n = nameOf.apply(r);
        out.add(norm(n == null ? r.name() : n));
      }
    }
    return out;
  }

  private static TreeSet<String> strings(Collection<String> values) {
    TreeSet<String> out = new TreeSet<>();
    if (values != null) {
      values.forEach(v -> out.add(norm(v)));
    }
    return out;
  }

  private static TreeSet<String> enums(Collection<? extends Enum<?>> values) {
    TreeSet<String> out = new TreeSet<>();
    if (values != null) {
      values.forEach(v -> out.add(v.name()));
    }
    return out;
  }

  private static String norm(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }
}
