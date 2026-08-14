package com.flatmaite.user;

import com.flatmaite.user.UserDtos.PreferencesRequest;
import com.flatmaite.user.UserDtos.ProfileRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {

  private final ProfileRepository profiles;
  private final UserPreferencesRepository preferences;

  @Transactional
  public Profile updateProfile(UUID userId, ProfileRequest req) {
    Profile p =
        profiles.findByUserId(userId).orElseGet(() -> Profile.builder().userId(userId).build());
    if (req.dateOfBirth() != null) p.setDateOfBirth(req.dateOfBirth());
    if (req.gender() != null) p.setGender(req.gender());
    if (req.occupation() != null) p.setOccupation(req.occupation());
    if (req.occupationDetail() != null) p.setOccupationDetail(req.occupationDetail());
    if (req.companyOrCollege() != null) p.setCompanyOrCollege(req.companyOrCollege());
    if (req.languages() != null) p.setLanguages(req.languages().toArray(String[]::new));
    if (req.bio() != null) p.setBio(req.bio());
    if (req.currentLocalityId() != null) p.setCurrentLocalityId(req.currentLocalityId());
    if (req.hometown() != null) p.setHometown(req.hometown());
    if (req.smoking() != null) p.setSmoking(req.smoking());
    if (req.drinking() != null) p.setDrinking(req.drinking());
    if (req.diet() != null) p.setDiet(req.diet());
    if (req.pets() != null) p.setPets(req.pets());
    if (req.sleepSchedule() != null) p.setSleepSchedule(req.sleepSchedule());
    if (req.wfhFrequency() != null) p.setWfhFrequency(req.wfhFrequency());
    if (req.cleanliness() != null) p.setCleanliness(req.cleanliness());
    if (req.socialStyle() != null) p.setSocialStyle(req.socialStyle());
    if (req.partyFrequency() != null) p.setPartyFrequency(req.partyFrequency());
    if (req.guestFrequency() != null) p.setGuestFrequency(req.guestFrequency());
    if (req.cookingFrequency() != null) p.setCookingFrequency(req.cookingFrequency());
    if (req.householdPref() != null) p.setHouseholdPref(req.householdPref());
    p.setProfileCompleteness(computeCompleteness(p));
    return profiles.save(p);
  }

  @Transactional
  public UserPreferences updatePreferences(UUID userId, PreferencesRequest req) {
    UserPreferences p =
        preferences
            .findByUserId(userId)
            .orElseGet(() -> UserPreferences.builder().userId(userId).build());
    if (req.budgetMin() != null) p.setBudgetMin(req.budgetMin());
    if (req.budgetMax() != null) p.setBudgetMax(req.budgetMax());
    if (req.localityIds() != null) p.setLocalityIds(req.localityIds().toArray(UUID[]::new));
    if (req.moveInFrom() != null) p.setMoveInFrom(req.moveInFrom());
    if (req.moveInTo() != null) p.setMoveInTo(req.moveInTo());
    if (req.leaseMonthsMin() != null) p.setLeaseMonthsMin(req.leaseMonthsMin().shortValue());
    if (req.leaseMonthsMax() != null) p.setLeaseMonthsMax(req.leaseMonthsMax().shortValue());
    if (req.roomType() != null) p.setRoomType(req.roomType());
    if (req.furnishing() != null) p.setFurnishing(req.furnishing().toArray(String[]::new));
    if (req.bhkMin() != null) p.setBhkMin(req.bhkMin().shortValue());
    if (req.bhkMax() != null) p.setBhkMax(req.bhkMax().shortValue());
    if (req.depositMax() != null) p.setDepositMax(req.depositMax());
    if (req.parkingNeeded() != null) p.setParkingNeeded(req.parkingNeeded());
    if (req.genderPref() != null) p.setGenderPref(req.genderPref());
    if (req.amenities() != null) p.setAmenities(req.amenities().toArray(String[]::new));
    if (req.notes() != null) p.setNotes(req.notes());
    return preferences.save(p);
  }

  static short computeCompleteness(Profile p) {
    Object[] weighted = {
      p.getDateOfBirth(), p.getGender(), p.getOccupation(), p.getBio(),
      p.getSmoking(), p.getDrinking(), p.getDiet(), p.getPets(),
      p.getSleepSchedule(), p.getWfhFrequency(), p.getCleanliness(), p.getSocialStyle(),
      p.getPartyFrequency(), p.getGuestFrequency(), p.getCookingFrequency(),
    };
    long filled = java.util.Arrays.stream(weighted).filter(java.util.Objects::nonNull).count();
    return (short) Math.round(filled * 100.0 / weighted.length);
  }
}
