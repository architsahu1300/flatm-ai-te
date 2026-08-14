package com.flatmaite.ai;

import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.listing.Listing;
import com.flatmaite.user.Profile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * Single place that composes the text fed to the embedding model, so listings, flatmate profiles
 * and seed all embed the same representation. Changing this composition invalidates stored
 * embeddings via the sha256 hash.
 */
public final class EmbeddingTextComposer {

  private EmbeddingTextComposer() {}

  public static String composeListing(Listing l, String localityName) {
    StringJoiner sj = new StringJoiner(". ");
    sj.add(l.getTitle());
    sj.add(l.getDescription());
    sj.add("Located in " + localityName + ", Mumbai");
    sj.add(l.getRoomType().name().toLowerCase() + " " + l.getType().name().toLowerCase().replace('_', ' '));
    sj.add(l.getFurnishing().name().toLowerCase().replace('_', ' '));
    if (Boolean.FALSE.equals(l.getHouseholdSmoking())) {
      sj.add("non-smoking household, no smokers");
    } else if (Boolean.TRUE.equals(l.getHouseholdSmoking())) {
      sj.add("smoking allowed");
    }
    if (Boolean.TRUE.equals(l.getHouseholdPets())) {
      sj.add("pets in the flat, pet friendly");
    }
    if (l.getHouseholdDiet() != null) {
      sj.add(l.getHouseholdDiet().name().toLowerCase().replace('_', ' ') + " household");
    }
    if (l.getHouseholdSocial() != null) {
      switch (l.getHouseholdSocial()) {
        case QUIET -> sj.add("quiet calm peaceful home, no parties");
        case BALANCED -> sj.add("balanced easy-going household");
        case VERY_SOCIAL -> sj.add("social lively flat, we host friends and parties");
      }
    }
    if (l.getOccupantsDesc() != null) {
      sj.add(l.getOccupantsDesc());
    }
    return sj.toString();
  }

  public static String composeFlatmate(FlatmateProfile fp, Profile profile, String localityNames) {
    StringJoiner sj = new StringJoiner(". ");
    sj.add(fp.getHeadline());
    sj.add(fp.getAbout());
    if (!localityNames.isBlank()) {
      sj.add("Preferred areas: " + localityNames);
    }
    if (profile != null) {
      if (profile.getOccupation() != null) {
        sj.add(profile.getOccupation().name().toLowerCase().replace('_', ' '));
      }
      if (profile.getSmoking() != null) {
        sj.add(
            switch (profile.getSmoking()) {
              case NEVER -> "non-smoker, does not smoke";
              case OCCASIONALLY -> "occasional smoker";
              case REGULARLY -> "smoker";
            });
      }
      if (profile.getDiet() != null) {
        sj.add(profile.getDiet().name().toLowerCase().replace('_', ' '));
      }
      if (profile.getSocialStyle() != null) {
        sj.add(
            switch (profile.getSocialStyle()) {
              case QUIET -> "quiet, prefers a calm peaceful home";
              case BALANCED -> "balanced social life";
              case VERY_SOCIAL -> "very social, loves hosting";
            });
      }
      if (profile.getSleepSchedule() != null) {
        sj.add(profile.getSleepSchedule().name().toLowerCase().replace('_', ' '));
      }
      if (profile.getCleanliness() != null) {
        sj.add("cleanliness: " + profile.getCleanliness().name().toLowerCase().replace('_', ' '));
      }
      if (profile.getWfhFrequency() != null) {
        sj.add(
            switch (profile.getWfhFrequency()) {
              case NEVER -> "works from office";
              case HYBRID -> "hybrid, works from home some days";
              case FULL_TIME -> "works from home full time";
            });
      }
    }
    return sj.toString();
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
