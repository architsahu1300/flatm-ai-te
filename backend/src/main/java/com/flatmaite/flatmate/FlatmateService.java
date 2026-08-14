package com.flatmaite.flatmate;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.EmbeddingTextComposer;
import com.flatmaite.ai.VectorStoreWriter;
import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.domain.VerificationType;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.flatmate.FlatmateDtos.CardResponse;
import com.flatmaite.flatmate.FlatmateDtos.DetailResponse;
import com.flatmaite.flatmate.FlatmateDtos.UpsertFlatmateProfileRequest;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.search.LifestyleCompatibility;
import com.flatmaite.user.Profile;
import com.flatmaite.user.ProfileRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import com.flatmaite.verification.Verification;
import com.flatmaite.verification.VerificationRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FlatmateService {

  private final FlatmateProfileRepository flatmateProfiles;
  private final ProfileRepository profiles;
  private final UserRepository users;
  private final LocalityRepository localities;
  private final VerificationRepository verifications;
  private final EmbeddingProvider embeddingProvider;
  private final VectorStoreWriter vectorWriter;

  @Transactional
  public FlatmateProfile upsertOwn(UUID userId, UpsertFlatmateProfileRequest req) {
    FlatmateProfile fp =
        flatmateProfiles
            .findByUserId(userId)
            .orElseGet(() -> FlatmateProfile.builder().userId(userId).headline("").build());
    fp.setHeadline(req.headline());
    if (req.about() != null) fp.setAbout(req.about());
    if (req.hasFlat() != null) fp.setHasFlat(req.hasFlat());
    if (req.budgetMin() != null) fp.setBudgetMin(req.budgetMin());
    if (req.budgetMax() != null) fp.setBudgetMax(req.budgetMax());
    if (req.localityIds() != null) fp.setLocalityIds(req.localityIds().toArray(UUID[]::new));
    if (req.moveInFrom() != null) fp.setMoveInFrom(req.moveInFrom());
    if (req.genderPref() != null) fp.setGenderPref(req.genderPref());
    FlatmateProfile saved = flatmateProfiles.save(fp);
    refreshEmbedding(saved.getId());
    return saved;
  }

  @Transactional
  public FlatmateProfile setActive(UUID userId, boolean active) {
    FlatmateProfile fp =
        flatmateProfiles
            .findByUserId(userId)
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "no_flatmate_profile", "Create your flatmate card first"));
    if (active && fp.getHeadline().isBlank()) {
      throw ApiException.badRequest("headline_required", "Add a headline before going live");
    }
    fp.setActive(active);
    return flatmateProfiles.save(fp);
  }

  @Transactional(readOnly = true)
  public List<CardResponse> browse(
      UUID viewerId, UUID localityId, Integer budgetMax, Boolean hasFlat, int page, int size) {
    List<FlatmateProfile> active =
        flatmateProfiles.findAll().stream()
            .filter(FlatmateProfile::isActive)
            .filter(fp -> viewerId == null || !fp.getUserId().equals(viewerId))
            .filter(fp -> localityId == null || List.of(fp.getLocalityIds()).contains(localityId))
            .filter(fp -> budgetMax == null || fp.getBudgetMin() == null || fp.getBudgetMin() <= budgetMax)
            .filter(fp -> hasFlat == null || fp.isHasFlat() == hasFlat)
            .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
            .toList();
    List<FlatmateProfile> pageItems =
        active.stream().skip((long) page * size).limit(size).toList();
    return assembleCards(pageItems, viewerId);
  }

  @Transactional(readOnly = true)
  public DetailResponse detail(UUID flatmateProfileId, UUID viewerId) {
    FlatmateProfile fp =
        flatmateProfiles
            .findById(flatmateProfileId)
            .filter(FlatmateProfile::isActive)
            .orElseThrow(() -> ApiException.notFound("Flatmate profile not found"));
    CardResponse card = assembleCards(List.of(fp), viewerId).get(0);
    Profile p = profiles.findByUserId(fp.getUserId()).orElse(null);
    return new DetailResponse(
        card,
        fp.getAbout(),
        p == null ? null : p.getBio(),
        p == null ? null : p.getCompanyOrCollege(),
        p == null ? List.of() : List.of(p.getLanguages()),
        enumName(p == null ? null : p.getSmoking()),
        enumName(p == null ? null : p.getDrinking()),
        enumName(p == null ? null : p.getDiet()),
        enumName(p == null ? null : p.getPets()),
        enumName(p == null ? null : p.getSleepSchedule()),
        enumName(p == null ? null : p.getWfhFrequency()),
        enumName(p == null ? null : p.getCleanliness()),
        enumName(p == null ? null : p.getSocialStyle()),
        enumName(p == null ? null : p.getPartyFrequency()),
        enumName(p == null ? null : p.getGuestFrequency()),
        enumName(p == null ? null : p.getCookingFrequency()));
  }

  public List<CardResponse> assembleCards(List<FlatmateProfile> items, UUID viewerId) {
    Map<UUID, User> userById = new HashMap<>();
    users.findAllById(items.stream().map(FlatmateProfile::getUserId).toList())
        .forEach(u -> userById.put(u.getId(), u));
    Map<UUID, Profile> profileByUser = new HashMap<>();
    profiles.findAll().forEach(p -> profileByUser.put(p.getUserId(), p));
    Map<UUID, String> localityNames = new HashMap<>();
    localities.findAll().forEach(l -> localityNames.put(l.getId(), l.getName()));
    Set<UUID> verified = new HashSet<>();
    for (Verification v :
        verifications.findByUserIdIn(items.stream().map(FlatmateProfile::getUserId).toList())) {
      if (v.getStatus() == VerificationStatus.VERIFIED
          && (v.getType() == VerificationType.GOV_ID || v.getType() == VerificationType.SELFIE)) {
        verified.add(v.getUserId());
      }
    }
    Profile viewerProfile = viewerId == null ? null : profileByUser.get(viewerId);

    List<CardResponse> cards = new ArrayList<>();
    for (FlatmateProfile fp : items) {
      User u = userById.get(fp.getUserId());
      Profile p = profileByUser.get(fp.getUserId());
      Integer compatibility = null;
      List<String> sharedTraits = List.of();
      if (viewerProfile != null && p != null) {
        LifestyleCompatibility.Result result = LifestyleCompatibility.between(viewerProfile, p);
        compatibility = (int) Math.round(result.score() * 100);
        sharedTraits = result.sharedTraits();
      }
      cards.add(
          new CardResponse(
              fp.getId(),
              fp.getUserId(),
              u == null ? "Member" : u.getName(),
              u == null ? null : u.getImage(),
              p == null || p.getDateOfBirth() == null
                  ? null
                  : Period.between(p.getDateOfBirth(), LocalDate.now()).getYears(),
              p == null ? null : enumName(p.getGender()),
              p == null ? null : enumName(p.getOccupation()),
              p == null ? null : p.getOccupationDetail(),
              fp.getHeadline(),
              fp.isHasFlat(),
              fp.getBudgetMin(),
              fp.getBudgetMax(),
              java.util.Arrays.stream(fp.getLocalityIds())
                  .map(localityNames::get)
                  .filter(java.util.Objects::nonNull)
                  .toList(),
              fp.getMoveInFrom(),
              lifestyleTags(p),
              verified.contains(fp.getUserId()),
              compatibility,
              sharedTraits));
    }
    return cards;
  }

  static List<String> lifestyleTags(Profile p) {
    if (p == null) {
      return List.of();
    }
    List<String> tags = new ArrayList<>();
    if (p.getSocialStyle() != null) {
      tags.add(
          switch (p.getSocialStyle()) {
            case QUIET -> "Quiet";
            case BALANCED -> "Easy-going";
            case VERY_SOCIAL -> "Social";
          });
    }
    if (p.getSmoking() == com.flatmaite.common.domain.SmokingHabit.NEVER) {
      tags.add("Non-smoker");
    }
    if (p.getDiet() != null) {
      tags.add(
          switch (p.getDiet()) {
            case VEGETARIAN -> "Vegetarian";
            case JAIN -> "Jain food";
            case VEGAN -> "Vegan";
            case EGGETARIAN -> "Eggetarian";
            case NON_VEGETARIAN -> "Non-veg";
          });
    }
    if (p.getSleepSchedule() != null) {
      tags.add(
          switch (p.getSleepSchedule()) {
            case EARLY_BIRD -> "Early riser";
            case FLEXIBLE -> "Flexible hours";
            case NIGHT_OWL -> "Night owl";
          });
    }
    if (p.getPets() == com.flatmaite.common.domain.PetsStance.HAS_PETS) {
      tags.add("Has a pet");
    }
    return tags.size() > 5 ? tags.subList(0, 5) : tags;
  }

  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void refreshEmbedding(UUID flatmateProfileId) {
    FlatmateProfile fp = flatmateProfiles.findById(flatmateProfileId).orElse(null);
    if (fp == null) {
      return;
    }
    Profile p = profiles.findByUserId(fp.getUserId()).orElse(null);
    Map<UUID, String> names = new HashMap<>();
    localities.findAll().forEach(l -> names.put(l.getId(), l.getName()));
    String locNames =
        java.util.Arrays.stream(fp.getLocalityIds())
            .map(names::get)
            .filter(java.util.Objects::nonNull)
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    String text = EmbeddingTextComposer.composeFlatmate(fp, p, locNames);
    String hash = EmbeddingTextComposer.sha256(text);
    if (!hash.equals(fp.getEmbeddingTextHash())) {
      vectorWriter.writeFlatmateEmbedding(fp.getId(), embeddingProvider.embed(text), hash);
    }
  }

  private static String enumName(Enum<?> e) {
    return e == null ? null : e.name();
  }
}
