package com.flatmaite.seed;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.EmbeddingTextComposer;
import com.flatmaite.ai.VectorStoreWriter;
import com.flatmaite.common.domain.CleanlinessLevel;
import com.flatmaite.common.domain.ConversationStatus;
import com.flatmaite.common.domain.CookingFrequency;
import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.DrinkingHabit;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.Gender;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.GuestFrequency;
import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.OccupationType;
import com.flatmaite.common.domain.PartyFrequency;
import com.flatmaite.common.domain.PetsStance;
import com.flatmaite.common.domain.PlanTier;
import com.flatmaite.common.domain.PropertyType;
import com.flatmaite.common.domain.ReportReason;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SleepSchedule;
import com.flatmaite.common.domain.SmokingHabit;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.common.domain.UserRole;
import com.flatmaite.common.domain.VerificationStatus;
import com.flatmaite.common.domain.VerificationType;
import com.flatmaite.common.domain.WfhFrequency;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.flatmate.FlatmateProfile;
import com.flatmaite.flatmate.FlatmateProfileRepository;
import com.flatmaite.listing.Amenity;
import com.flatmaite.listing.AmenityRepository;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingImage;
import com.flatmaite.listing.ListingRepository;
import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import com.flatmaite.listing.Property;
import com.flatmaite.listing.PropertyRepository;
import com.flatmaite.messaging.Conversation;
import com.flatmaite.messaging.ConversationRepository;
import com.flatmaite.messaging.Message;
import com.flatmaite.messaging.MessageRepository;
import com.flatmaite.payment.Plan;
import com.flatmaite.payment.PlanRepository;
import com.flatmaite.report.Report;
import com.flatmaite.report.ReportRepository;
import com.flatmaite.saved.SavedListing;
import com.flatmaite.saved.SavedListingRepository;
import com.flatmaite.saved.SavedSearch;
import com.flatmaite.saved.SavedSearchRepository;
import com.flatmaite.user.Profile;
import com.flatmaite.user.ProfileRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserPreferences;
import com.flatmaite.user.UserPreferencesRepository;
import com.flatmaite.user.UserRepository;
import com.flatmaite.verification.Verification;
import com.flatmaite.verification.VerificationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, idempotent seed. All ids come from UUIDv3 over stable keys; all randomness from a
 * fixed-seed PRNG — re-running upserts the same rows. Activate with the "seed" profile (runs and
 * exits, no web server).
 */
@Component
@org.springframework.context.annotation.Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class SeedRunner implements ApplicationRunner {

  private final UserRepository users;
  private final ProfileRepository profiles;
  private final UserPreferencesRepository preferences;
  private final LocalityRepository localities;
  private final AmenityRepository amenities;
  private final PropertyRepository properties;
  private final ListingRepository listings;
  private final FlatmateProfileRepository flatmateProfiles;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final SavedListingRepository savedListings;
  private final SavedSearchRepository savedSearches;
  private final ReportRepository reports;
  private final VerificationRepository verifications;
  private final PlanRepository plans;
  private final PasswordEncoder passwordEncoder;
  private final EmbeddingProvider embeddingProvider;
  private final VectorStoreWriter vectorWriter;
  private final FlatmaiteProperties props;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  private final Random rng = new Random(42);
  private final Faker faker = new Faker(new Locale("en", "IND"), new Random(42));

  private static final int USER_COUNT = 54;
  private static final int LISTING_COUNT = 50;
  private static final int FLATMATE_COUNT = 35;
  private static final int IMAGE_COUNT = 15;

  private record LocalitySeed(String name, double lat, double lng, String[] aliases, int rentBand) {}

  // rentBand = typical private-room rent midpoint (₹/month)
  private static final LocalitySeed[] LOCALITIES = {
    new LocalitySeed("Andheri", 19.1136, 72.8697, new String[] {"andheri east", "andheri west"}, 22000),
    new LocalitySeed("Bandra", 19.0596, 72.8295, new String[] {"bandra west", "bandra east"}, 32000),
    new LocalitySeed("Powai", 19.1176, 72.9060, new String[] {"hiranandani"}, 24000),
    new LocalitySeed("Lower Parel", 18.9962, 72.8330, new String[] {"parel"}, 33000),
    new LocalitySeed("Worli", 19.0176, 72.8172, new String[] {}, 38000),
    new LocalitySeed("Goregaon", 19.1663, 72.8526, new String[] {"goregaon east", "goregaon west"}, 16000),
    new LocalitySeed("Malad", 19.1874, 72.8484, new String[] {"malad west"}, 14000),
    new LocalitySeed("BKC", 19.0653, 72.8693, new String[] {"bandra kurla complex", "bandra-kurla"}, 36000),
    new LocalitySeed("Kurla", 19.0726, 72.8845, new String[] {"kurla west"}, 13000),
    new LocalitySeed("Ghatkopar", 19.0790, 72.9080, new String[] {"ghatkopar east"}, 15000),
  };

  private static final String[][] AMENITY_SEED = {
    {"wifi", "WiFi", "connectivity"}, {"ac", "Air Conditioning", "comfort"},
    {"washing_machine", "Washing Machine", "appliances"}, {"fridge", "Refrigerator", "appliances"},
    {"microwave", "Microwave", "appliances"}, {"geyser", "Geyser", "comfort"},
    {"parking_2w", "2-Wheeler Parking", "parking"}, {"parking_4w", "Car Parking", "parking"},
    {"gym", "Gym", "society"}, {"swimming_pool", "Swimming Pool", "society"},
    {"security", "24x7 Security", "society"}, {"lift", "Lift", "society"},
    {"power_backup", "Power Backup", "society"}, {"balcony", "Balcony", "comfort"},
    {"wardrobe", "Wardrobe", "furniture"}, {"tv", "Television", "appliances"},
    {"cook", "Cook Available", "services"}, {"maid", "Maid Available", "services"},
    {"water_purifier", "Water Purifier", "appliances"}, {"gas_pipeline", "Piped Gas", "kitchen"},
  };

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    log.info("Seeding Flatm'AI'te (embedding provider: {})", embeddingProvider.providerName());
    writePlaceholderImages();
    List<Locality> locs = seedLocalities();
    List<Amenity> amens = seedAmenities();
    seedPlans();
    List<User> allUsers = seedUsers();
    List<Profile> allProfiles = seedProfiles(allUsers, locs);
    seedPreferences(allUsers, locs);
    List<Listing> allListings = seedListings(allUsers, locs, amens);
    List<FlatmateProfile> fms = seedFlatmates(allUsers, allProfiles, locs);
    seedVerifications(allUsers);
    seedConversations(allUsers, allListings);
    seedSavedContent(allUsers, allListings);
    seedReports(allUsers, allListings);
    embedListings(allListings, locs);
    embedFlatmates(fms, allProfiles, locs);
    log.info(
        "Seed complete: {} users, {} listings, {} flatmate profiles, {} localities",
        allUsers.size(),
        allListings.size(),
        fms.size(),
        locs.size());
  }

  private static UUID uuid(String key) {
    return UUID.nameUUIDFromBytes(("flatmaite:" + key).getBytes(StandardCharsets.UTF_8));
  }

  private <T> T pick(T[] options) {
    return options[rng.nextInt(options.length)];
  }

  /** Order-stable weighted pick — Map.of iteration order is salted per JVM run, so varargs only. */
  @SafeVarargs
  private final <T> T weighted(Map.Entry<T, Integer>... entries) {
    int total = 0;
    for (var e : entries) {
      total += e.getValue();
    }
    int roll = rng.nextInt(total);
    for (var e : entries) {
      roll -= e.getValue();
      if (roll < 0) {
        return e.getKey();
      }
    }
    throw new IllegalStateException();
  }

  private PartyFrequency partyFrequencyFor(SocialStyle social) {
    PartyFrequency drawn =
        weighted(
            Map.entry(PartyFrequency.NEVER, 30),
            Map.entry(PartyFrequency.OCCASIONALLY, 55),
            Map.entry(PartyFrequency.FREQUENTLY, 15));
    return social == SocialStyle.QUIET ? PartyFrequency.NEVER : drawn;
  }

  // ---------- reference ----------

  private void writePlaceholderImages() throws Exception {
    Path dir = Paths.get(props.getStorage().getUploadDir(), "seed");
    Files.createDirectories(dir);
    String[][] palettes = {
      {"#e8eaf6", "#c5cae9"}, {"#e0f2f1", "#b2dfdb"}, {"#fff3e0", "#ffe0b2"},
      {"#fce4ec", "#f8bbd0"}, {"#e8f5e9", "#c8e6c9"}, {"#ede7f6", "#d1c4e9"},
      {"#e1f5fe", "#b3e5fc"}, {"#f3e5f5", "#e1bee7"}, {"#fffde7", "#fff9c4"},
      {"#efebe9", "#d7ccc8"}, {"#eceff1", "#cfd8dc"}, {"#e0f7fa", "#b2ebf2"},
      {"#f1f8e9", "#dcedc8"}, {"#fbe9e7", "#ffccbc"}, {"#f9fbe7", "#f0f4c3"},
    };
    String[] labels = {
      "Living Room", "Bedroom", "Kitchen", "Balcony View", "Master Bedroom",
      "Hall", "Study Corner", "Dining Area", "Bedroom 2", "Terrace",
      "Living Area", "Compact Room", "Sea View", "Society Garden", "Workspace",
    };
    for (int i = 0; i < IMAGE_COUNT; i++) {
      String svg =
          """
          <svg xmlns="http://www.w3.org/2000/svg" width="800" height="500" viewBox="0 0 800 500">
            <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%%" stop-color="%s"/><stop offset="100%%" stop-color="%s"/>
            </linearGradient></defs>
            <rect width="800" height="500" fill="url(#g)"/>
            <g fill="#71717a" font-family="system-ui, sans-serif" text-anchor="middle">
              <text x="400" y="245" font-size="30" font-weight="600">%s</text>
              <text x="400" y="280" font-size="16">Flatm'AI'te sample photo</text>
            </g>
          </svg>
          """
              .formatted(palettes[i][0], palettes[i][1], labels[i]);
      Files.writeString(dir.resolve("img-%02d.svg".formatted(i + 1)), svg);
    }
  }

  private List<Locality> seedLocalities() {
    List<Locality> out = new ArrayList<>();
    for (LocalitySeed ls : LOCALITIES) {
      Locality l =
          Locality.builder().name(ls.name()).lat(ls.lat()).lng(ls.lng()).aliases(ls.aliases()).build();
      l.setId(uuid("locality:" + ls.name()));
      out.add(localities.save(l));
    }
    return out;
  }

  private List<Amenity> seedAmenities() {
    List<Amenity> out = new ArrayList<>();
    for (String[] a : AMENITY_SEED) {
      Amenity am = Amenity.builder().slug(a[0]).label(a[1]).category(a[2]).build();
      am.setId(uuid("amenity:" + a[0]));
      out.add(amenities.save(am));
    }
    return out;
  }

  private void seedPlans() {
    Plan free =
        Plan.builder()
            .slug("free")
            .name("Free")
            .tier(PlanTier.FREE)
            .priceMonthly(BigDecimal.ZERO)
            .features("[\"Basic search\",\"AI searches (daily cap)\",\"Create listings\",\"Messaging\"]")
            .build();
    free.setId(uuid("plan:free"));
    plans.save(free);
    Plan premium =
        Plan.builder()
            .slug("premium")
            .name("Premium Seeker")
            .tier(PlanTier.PREMIUM)
            .priceMonthly(new BigDecimal("299.00"))
            .features(
                "[\"Unlimited AI searches\",\"Advanced matching\",\"Match alerts\",\"Compatibility reports\",\"Early access to new listings\"]")
            .build();
    premium.setId(uuid("plan:premium"));
    plans.save(premium);
  }

  // ---------- people ----------

  private List<User> seedUsers() {
    List<User> out = new ArrayList<>();
    String passwordHash = passwordEncoder.encode("password123");
    for (int i = 1; i <= USER_COUNT; i++) {
      String key = "user:%03d".formatted(i);
      boolean emailVerified = rng.nextInt(100) < 75;
      boolean phoneVerified = rng.nextInt(100) < 60;
      User u =
          User.builder()
              .email("seed-user-%03d@flatmaite.test".formatted(i))
              .emailVerifiedAt(emailVerified ? Instant.parse("2026-07-01T00:00:00Z") : null)
              .phone("98%08d".formatted(20000000 + i))
              .phoneVerifiedAt(phoneVerified ? Instant.parse("2026-07-01T00:00:00Z") : null)
              .passwordHash(passwordHash)
              .name(faker.name().fullName())
              .role(UserRole.USER)
              .lastActiveAt(Instant.now().minusSeconds(rng.nextInt(14 * 24 * 3600)))
              .build();
      u.setId(uuid(key));
      out.add(users.save(u));
    }
    User admin =
        User.builder()
            .email("admin@flatmaite.test")
            .emailVerifiedAt(Instant.parse("2026-07-01T00:00:00Z"))
            .phone("9810000000")
            .passwordHash(passwordHash)
            .name("Flatmaite Admin")
            .role(UserRole.ADMIN)
            .build();
    admin.setId(uuid("user:admin"));
    out.add(users.save(admin));
    return out;
  }

  private List<Profile> seedProfiles(List<User> allUsers, List<Locality> locs) {
    List<Profile> out = new ArrayList<>();
    for (int i = 0; i < allUsers.size(); i++) {
      User u = allUsers.get(i);
      SocialStyle social =
          weighted(Map.entry(SocialStyle.QUIET, 30), Map.entry(SocialStyle.BALANCED, 50), Map.entry(SocialStyle.VERY_SOCIAL, 20));
      SmokingHabit smoking =
          weighted(Map.entry(SmokingHabit.NEVER, 65), Map.entry(SmokingHabit.OCCASIONALLY, 25), Map.entry(SmokingHabit.REGULARLY, 10));
      Diet diet =
          weighted(Map.entry(Diet.VEGETARIAN, 42), Map.entry(Diet.EGGETARIAN, 10), Map.entry(Diet.NON_VEGETARIAN, 42), Map.entry(Diet.VEGAN, 3), Map.entry(Diet.JAIN, 3));
      OccupationType occupation =
          weighted(Map.entry(OccupationType.STUDENT, 15), Map.entry(OccupationType.WORKING_PROFESSIONAL, 68), Map.entry(OccupationType.FREELANCER, 10), Map.entry(OccupationType.BUSINESS_OWNER, 4), Map.entry(OccupationType.OTHER, 3));
      String jobTitle = faker.job().title();
      String companyName = faker.company().name();
      Profile p =
          Profile.builder()
              .userId(u.getId())
              .dateOfBirth(LocalDate.of(1991 + rng.nextInt(14), 1 + rng.nextInt(12), 1 + rng.nextInt(28)))
              .gender(weighted(Map.entry(Gender.MALE, 50), Map.entry(Gender.FEMALE, 45), Map.entry(Gender.NON_BINARY, 3), Map.entry(Gender.PREFER_NOT_TO_SAY, 2)))
              .occupation(occupation)
              .occupationDetail(
                  occupation == OccupationType.WORKING_PROFESSIONAL ? jobTitle : null)
              .companyOrCollege(
                  occupation == OccupationType.STUDENT ? "Mumbai University" : companyName)
              .languages(new String[] {"english", "hindi"})
              .currentLocalityId(locs.get(rng.nextInt(locs.size())).getId())
              .smoking(smoking)
              .drinking(weighted(Map.entry(DrinkingHabit.NEVER, 40), Map.entry(DrinkingHabit.SOCIALLY, 45), Map.entry(DrinkingHabit.REGULARLY, 15)))
              .diet(diet)
              .pets(weighted(Map.entry(PetsStance.HAS_PETS, 8), Map.entry(PetsStance.LOVES_PETS, 15), Map.entry(PetsStance.OK_WITH_PETS, 45), Map.entry(PetsStance.NO_PETS, 32)))
              .sleepSchedule(weighted(Map.entry(SleepSchedule.EARLY_BIRD, 30), Map.entry(SleepSchedule.FLEXIBLE, 40), Map.entry(SleepSchedule.NIGHT_OWL, 30)))
              .wfhFrequency(weighted(Map.entry(WfhFrequency.NEVER, 30), Map.entry(WfhFrequency.HYBRID, 50), Map.entry(WfhFrequency.FULL_TIME, 20)))
              .cleanliness(weighted(Map.entry(CleanlinessLevel.RELAXED, 15), Map.entry(CleanlinessLevel.AVERAGE, 50), Map.entry(CleanlinessLevel.VERY_TIDY, 35)))
              .socialStyle(social)
              .partyFrequency(partyFrequencyFor(social))
              .guestFrequency(weighted(Map.entry(GuestFrequency.RARELY, 40), Map.entry(GuestFrequency.SOMETIMES, 45), Map.entry(GuestFrequency.OFTEN, 15)))
              .cookingFrequency(weighted(Map.entry(CookingFrequency.NEVER, 20), Map.entry(CookingFrequency.SOMETIMES, 50), Map.entry(CookingFrequency.DAILY, 30)))
              .bio(buildBio(social, smoking, diet))
              .profileCompleteness((short) (70 + rng.nextInt(31)))
              .build();
      p.setId(uuid("profile:" + u.getId()));
      out.add(profiles.save(p));
    }
    return out;
  }

  private String buildBio(SocialStyle social, SmokingHabit smoking, Diet diet) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        switch (social) {
          case QUIET -> "I keep to myself mostly and value a calm, quiet home. ";
          case BALANCED -> "Easy-going and friendly, happy to hang out or give space. ";
          case VERY_SOCIAL -> "Love meeting people and hosting friends over weekends. ";
        });
    if (smoking == SmokingHabit.NEVER) {
      sb.append("Non-smoker. ");
    }
    sb.append(
        switch (diet) {
          case VEGETARIAN, JAIN, VEGAN -> "Vegetarian food only at home. ";
          case EGGETARIAN -> "Mostly veg, eggs are fine. ";
          case NON_VEGETARIAN -> "Foodie — cook and eat everything. ";
        });
    sb.append(faker.lorem().sentence(8));
    return sb.toString();
  }

  private void seedPreferences(List<User> allUsers, List<Locality> locs) {
    for (int i = 0; i < 40; i++) {
      User u = allUsers.get(i);
      int band = LOCALITIES[i % LOCALITIES.length].rentBand();
      int budgetMax = (int) (band * (0.8 + rng.nextDouble() * 0.6));
      UserPreferences p =
          UserPreferences.builder()
              .userId(u.getId())
              .budgetMin(Math.max(5000, budgetMax - 8000 - rng.nextInt(4000)))
              .budgetMax(budgetMax)
              .localityIds(
                  new UUID[] {
                    locs.get(i % locs.size()).getId(), locs.get((i + 3) % locs.size()).getId()
                  })
              .moveInFrom(LocalDate.now().plusDays(rng.nextInt(45)))
              .roomType(pick(new RoomType[] {RoomType.PRIVATE, RoomType.PRIVATE, RoomType.SHARED, RoomType.ENTIRE}))
              .furnishing(new String[] {"SEMI_FURNISHED", "FULLY_FURNISHED"})
              .depositMax(budgetMax * (2 + rng.nextInt(3)))
              .genderPref(GenderPreference.ANY)
              .amenities(new String[] {"wifi", "washing_machine"})
              .build();
      p.setId(uuid("prefs:" + u.getId()));
      preferences.save(p);
    }
  }

  // ---------- listings ----------

  private List<Listing> seedListings(List<User> allUsers, List<Locality> locs, List<Amenity> amens) {
    List<Listing> out = new ArrayList<>();
    List<UUID> expectedImageIds = new ArrayList<>();
    ListingType[] types = new ListingType[LISTING_COUNT];
    int idx = 0;
    for (int i = 0; i < 18; i++) types[idx++] = ListingType.PRIVATE_ROOM;
    for (int i = 0; i < 10; i++) types[idx++] = ListingType.SHARED_ROOM;
    for (int i = 0; i < 10; i++) types[idx++] = ListingType.ENTIRE_APARTMENT;
    for (int i = 0; i < 8; i++) types[idx++] = ListingType.LOOKING_FOR_FLATMATE;
    for (int i = 0; i < 4; i++) types[idx++] = ListingType.REPLACEMENT;

    for (int i = 0; i < LISTING_COUNT; i++) {
      ListingType type = types[i];
      LocalitySeed ls = LOCALITIES[i % LOCALITIES.length];
      Locality loc = locs.get(i % locs.size());
      User lister = allUsers.get(10 + (i % 40)); // users 11..50 are listers
      short bhk = (short) (1 + rng.nextInt(3));

      Property prop =
          Property.builder()
              .ownerId(lister.getId())
              .localityId(loc.getId())
              .addressLine(faker.address().streetAddress())
              .societyName(faker.company().name() + " Heights")
              .pincode("4000" + (10 + rng.nextInt(90)))
              .lat(ls.lat() + (rng.nextDouble() - 0.5) * 0.015)
              .lng(ls.lng() + (rng.nextDouble() - 0.5) * 0.015)
              .propertyType(rng.nextInt(100) < 85 ? PropertyType.APARTMENT : PropertyType.PG)
              .bhk(bhk)
              .totalBathrooms((short) Math.max(1, bhk - rng.nextInt(2)))
              .floorNumber((short) (1 + rng.nextInt(20)))
              .totalFloors((short) (21 + rng.nextInt(10)))
              .builtUpSqft(380 * bhk + rng.nextInt(300))
              .ageYears((short) rng.nextInt(25))
              .build();
      prop.setId(uuid("property:" + i));
      properties.save(prop);

      RoomType roomType =
          switch (type) {
            case ENTIRE_APARTMENT -> RoomType.ENTIRE;
            case SHARED_ROOM -> RoomType.SHARED;
            default -> RoomType.PRIVATE;
          };
      int rent = rentFor(ls.rentBand(), type, bhk);
      SocialStyle vibe =
          weighted(Map.entry(SocialStyle.QUIET, 35), Map.entry(SocialStyle.BALANCED, 45), Map.entry(SocialStyle.VERY_SOCIAL, 20));
      boolean smoking = rng.nextInt(100) < 20;
      boolean pets = rng.nextInt(100) < 15;
      Diet dietPref = rng.nextInt(100) < 30 ? Diet.VEGETARIAN : null;
      Furnishing furn =
          weighted(Map.entry(Furnishing.UNFURNISHED, 15), Map.entry(Furnishing.SEMI_FURNISHED, 40), Map.entry(Furnishing.FULLY_FURNISHED, 45));
      ListingStatus status =
          i < 42
              ? ListingStatus.ACTIVE
              : switch (i % 4) {
                case 0 -> ListingStatus.DRAFT;
                case 1 -> ListingStatus.PAUSED;
                case 2 -> ListingStatus.RENTED;
                default -> ListingStatus.EXPIRED;
              };

      Listing l =
          Listing.builder()
              .propertyId(prop.getId())
              .listerId(lister.getId())
              .type(type)
              .status(status)
              .title(buildTitle(type, roomType, bhk, ls.name(), furn))
              .description(buildDescription(type, vibe, smoking, pets, dietPref, furn, ls.name()))
              .rentMonthly(rent)
              .deposit(rent * (2 + rng.nextInt(4)))
              .maintenanceMonthly(type == ListingType.ENTIRE_APARTMENT ? 2000 + rng.nextInt(4000) : 0)
              .availableFrom(LocalDate.now().plusDays(rng.nextInt(60)))
              .minLeaseMonths((short) (rng.nextInt(100) < 70 ? 11 : 6))
              .roomType(roomType)
              .furnishing(furn)
              .bathroomAttached(roomType == RoomType.PRIVATE ? rng.nextBoolean() : null)
              .balcony(rng.nextInt(100) < 40)
              .preferredGender(
                  weighted(Map.entry(GenderPreference.ANY, 60), Map.entry(GenderPreference.FEMALE_ONLY, 25), Map.entry(GenderPreference.MALE_ONLY, 15)))
              .couplesAllowed(rng.nextInt(100) < 25)
              .householdSmoking(type == ListingType.ENTIRE_APARTMENT ? null : smoking)
              .householdPets(type == ListingType.ENTIRE_APARTMENT ? null : pets)
              .householdDiet(dietPref)
              .householdSocial(type == ListingType.ENTIRE_APARTMENT ? null : vibe)
              .occupantsDesc(type == ListingType.ENTIRE_APARTMENT ? null : buildOccupantsDesc())
              .build();
      l.setId(uuid("listing:" + i));

      int photoCount = 2 + rng.nextInt(3);
      for (int p = 0; p < photoCount; p++) {
        ListingImage img =
            ListingImage.builder()
                .url("/uploads/seed/img-%02d.svg".formatted(1 + (i + p * 3) % IMAGE_COUNT))
                .sortOrder((short) p)
                .isCover(p == 0)
                .width(800)
                .height(500)
                .build();
        img.setId(uuid("listing:" + i + ":img:" + p));
        expectedImageIds.add(img.getId());
        l.getImages().add(img);
      }
      int amenityCount = 4 + rng.nextInt(6);
      for (int a = 0; a < amenityCount; a++) {
        l.getAmenities().add(amens.get((i * 3 + a * 2) % amens.size()));
      }
      l.setQualityScore(
          (float)
              (0.4 * Math.min(photoCount / 4.0, 1)
                  + 0.3 * Math.min(l.getDescription().length() / 400.0, 1)
                  + 0.3));
      listings
          .findById(l.getId())
          .ifPresent(existing -> l.setEmbeddingTextHash(existing.getEmbeddingTextHash()));
      out.add(listings.save(l));
    }
    // orphanRemoval does not fire on detached-merge; drop stale images from earlier seed versions
    int strays =
        jdbcTemplate.update(
            "DELETE FROM listing_images WHERE id <> ALL (?)",
            (Object) expectedImageIds.toArray(new UUID[0]));
    if (strays > 0) {
      log.info("Removed {} stray listing images", strays);
    }
    return out;
  }

  private int rentFor(int band, ListingType type, short bhk) {
    double factor =
        switch (type) {
          case ENTIRE_APARTMENT -> 1.4 + 0.7 * bhk;
          case SHARED_ROOM -> 0.55;
          default -> 1.0;
        };
    double jitter = 0.8 + rng.nextDouble() * 0.4;
    return (int) (Math.round(band * factor * jitter / 500.0) * 500);
  }

  private String buildTitle(ListingType type, RoomType roomType, short bhk, String locality, Furnishing furn) {
    String furnWord =
        switch (furn) {
          case FULLY_FURNISHED -> "Furnished";
          case SEMI_FURNISHED -> "Semi-furnished";
          case UNFURNISHED -> "Unfurnished";
        };
    return switch (type) {
      case ENTIRE_APARTMENT -> "%s %dBHK apartment in %s".formatted(furnWord, bhk, locality);
      case PRIVATE_ROOM -> "%s private room in %dBHK, %s".formatted(furnWord, bhk, locality);
      case SHARED_ROOM -> "Shared room in %dBHK, %s".formatted(bhk, locality);
      case LOOKING_FOR_FLATMATE -> "Flatmate wanted for %dBHK in %s".formatted(bhk, locality);
      case REPLACEMENT -> "Replacement flatmate needed — %dBHK in %s".formatted(bhk, locality);
    };
  }

  private String buildDescription(
      ListingType type, SocialStyle vibe, boolean smoking, boolean pets, Diet diet, Furnishing furn, String locality) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        switch (type) {
          case ENTIRE_APARTMENT -> "Well-maintained apartment available for rent in " + locality + ". ";
          case PRIVATE_ROOM -> "One private room available in our flat in " + locality + ". ";
          case SHARED_ROOM -> "Bed available in a shared room in " + locality + ". ";
          case LOOKING_FOR_FLATMATE -> "We're looking for a flatmate to join our place in " + locality + ". ";
          case REPLACEMENT -> "Current flatmate is moving out, so we need a replacement in " + locality + ". ";
        });
    if (type != ListingType.ENTIRE_APARTMENT) {
      sb.append(
          switch (vibe) {
            case QUIET -> "The house is calm and quiet — no parties, everyone values their peace. ";
            case BALANCED -> "Chilled-out household, we hang out sometimes but respect each other's space. ";
            case VERY_SOCIAL -> "Fun, social flat — we host friends often and love a good weekend party. ";
          });
      sb.append(smoking ? "Smoking is okay on the balcony. " : "Strictly no smoking inside the flat. ");
      if (pets) {
        sb.append("We have a friendly pet at home. ");
      }
      if (diet == Diet.VEGETARIAN) {
        sb.append("Vegetarian-only kitchen. ");
      }
    }
    sb.append(
        switch (furn) {
          case FULLY_FURNISHED -> "Comes fully furnished with bed, wardrobe and more. ";
          case SEMI_FURNISHED -> "Semi-furnished with the essentials. ";
          case UNFURNISHED -> "Unfurnished — bring your own furniture. ";
        });
    sb.append("Close to good restaurants, and well connected by metro and local trains.");
    return sb.toString();
  }

  private String buildOccupantsDesc() {
    int count = 1 + rng.nextInt(3);
    String who = pick(new String[] {"working professionals", "young professionals", "postgrad students"});
    return count + (count == 1 ? " flatmate currently, " : " flatmates currently, ") + who + " in their 20s";
  }

  // ---------- flatmates ----------

  private List<FlatmateProfile> seedFlatmates(List<User> allUsers, List<Profile> allProfiles, List<Locality> locs) {
    List<FlatmateProfile> out = new ArrayList<>();
    for (int i = 0; i < FLATMATE_COUNT; i++) {
      User u = allUsers.get(i);
      Profile p = allProfiles.get(i);
      LocalitySeed band = LOCALITIES[(i + 2) % LOCALITIES.length];
      boolean hasFlat = i % 4 == 0;
      int budgetMax = (int) (band.rentBand() * (0.75 + rng.nextDouble() * 0.5));
      String occupationWord =
          p.getOccupation() == OccupationType.STUDENT ? "student" : "working professional";
      FlatmateProfile fp =
          FlatmateProfile.builder()
              .userId(u.getId())
              .headline(
                  hasFlat
                      ? "Have a flat in %s — looking for a flatmate".formatted(band.name())
                      : "%s looking for a %s in %s"
                          .formatted(
                              occupationWord.substring(0, 1).toUpperCase() + occupationWord.substring(1),
                              pick(new String[] {"room", "flat share", "2BHK to share"}),
                              band.name()))
              .about(p.getBio())
              .isActive(true)
              .hasFlat(hasFlat)
              .budgetMin(Math.max(5000, budgetMax - 7000))
              .budgetMax(budgetMax)
              .localityIds(
                  new UUID[] {
                    locs.get((i + 2) % locs.size()).getId(), locs.get((i + 5) % locs.size()).getId()
                  })
              .moveInFrom(LocalDate.now().plusDays(rng.nextInt(45)))
              .genderPref(GenderPreference.ANY)
              .build();
      fp.setId(uuid("flatmate:" + u.getId()));
      flatmateProfiles
          .findById(fp.getId())
          .ifPresent(existing -> fp.setEmbeddingTextHash(existing.getEmbeddingTextHash()));
      out.add(flatmateProfiles.save(fp));
    }
    return out;
  }

  // ---------- supporting content ----------

  private void seedVerifications(List<User> allUsers) {
    for (User u : allUsers) {
      if (u.getEmailVerifiedAt() != null) {
        Verification v =
            Verification.builder()
                .userId(u.getId())
                .type(VerificationType.EMAIL)
                .status(VerificationStatus.VERIFIED)
                .build();
        v.setId(uuid("verif:email:" + u.getId()));
        verifications.save(v);
      }
      if (u.getPhoneVerifiedAt() != null) {
        Verification v =
            Verification.builder()
                .userId(u.getId())
                .type(VerificationType.PHONE)
                .status(VerificationStatus.VERIFIED)
                .build();
        v.setId(uuid("verif:phone:" + u.getId()));
        verifications.save(v);
      }
    }
    // A handful of gov-id verified users
    for (int i = 1; i <= 12; i++) {
      Verification v =
          Verification.builder()
              .userId(allUsers.get(i).getId())
              .type(VerificationType.GOV_ID)
              .status(VerificationStatus.VERIFIED)
              .build();
      v.setId(uuid("verif:govid:" + allUsers.get(i).getId()));
      verifications.save(v);
    }
  }

  private void seedConversations(List<User> allUsers, List<Listing> allListings) {
    ConversationStatus[] statuses = {
      ConversationStatus.ACCEPTED, ConversationStatus.ACCEPTED, ConversationStatus.ACCEPTED,
      ConversationStatus.ACCEPTED, ConversationStatus.ACCEPTED, ConversationStatus.PENDING,
      ConversationStatus.PENDING, ConversationStatus.PENDING, ConversationStatus.REJECTED,
      ConversationStatus.BLOCKED,
    };
    for (int i = 0; i < statuses.length; i++) {
      Listing listing = allListings.get(i * 3);
      User initiator = allUsers.get(i);
      UUID recipient = listing.getListerId();
      if (initiator.getId().equals(recipient)) {
        initiator = allUsers.get(i + 1);
      }
      Conversation c =
          Conversation.builder()
              .listingId(listing.getId())
              .initiatorId(initiator.getId())
              .recipientId(recipient)
              .status(statuses[i])
              .blockedBy(statuses[i] == ConversationStatus.BLOCKED ? recipient : null)
              .lastMessageAt(Instant.now().minusSeconds(3600L * (i + 1)))
              .build();
      c.setId(uuid("conversation:" + i));
      conversations.save(c);

      String[] script = {
        "Hi! I saw your listing — is the room still available?",
        "Yes it is! When are you looking to move in?",
        "Around the 1st of next month. Could I come see the place this weekend?",
        "Sure, Saturday afternoon works. I'll share the details here.",
      };
      int msgCount = statuses[i] == ConversationStatus.ACCEPTED ? 4 : 1;
      for (int m = 0; m < msgCount; m++) {
        Message msg =
            Message.builder()
                .conversationId(c.getId())
                .senderId(m % 2 == 0 ? c.getInitiatorId() : c.getRecipientId())
                .body(script[m])
                .readAt(m < msgCount - 1 ? Instant.now().minusSeconds(1800) : null)
                .build();
        msg.setId(uuid("conversation:" + i + ":msg:" + m));
        messages.save(msg);
      }
    }
  }

  private void seedSavedContent(List<User> allUsers, List<Listing> allListings) {
    for (int i = 0; i < 15; i++) {
      User u = allUsers.get(i % 8);
      Listing l = allListings.get((i * 2) % allListings.size());
      SavedListing sl =
          SavedListing.builder()
              .key(new SavedListing.Key(u.getId(), l.getId()))
              .note(i % 3 == 0 ? "Shortlisted — visit this weekend" : null)
              .build();
      savedListings.save(sl);
    }
    String[][] searches = {
      {"Room near BKC under 25k", "{\"searchTarget\":\"PROPERTIES\",\"locations\":[{\"name\":\"BKC\"}],\"budgetMax\":25000,\"roomType\":\"PRIVATE\",\"freeText\":\"room near BKC\"}"},
      {"Quiet flatmate in Andheri", "{\"searchTarget\":\"FLATMATES\",\"locations\":[{\"name\":\"Andheri\"}],\"lifestyle\":{\"quiet\":true},\"freeText\":\"quiet flatmate\"}"},
      {"Furnished 2BHK Powai", "{\"searchTarget\":\"PROPERTIES\",\"locations\":[{\"name\":\"Powai\"}],\"furnished\":\"FULLY_FURNISHED\",\"bhk\":{\"min\":2,\"max\":2},\"freeText\":\"furnished 2bhk\"}"},
      {"Shared room under 12k", "{\"searchTarget\":\"PROPERTIES\",\"budgetMax\":12000,\"roomType\":\"SHARED\",\"freeText\":\"cheap shared room\"}"},
      {"Veg household Ghatkopar", "{\"searchTarget\":\"PROPERTIES\",\"locations\":[{\"name\":\"Ghatkopar\"}],\"lifestyle\":{\"diet\":\"VEGETARIAN\"},\"freeText\":\"vegetarian household\"}"},
    };
    for (int i = 0; i < searches.length; i++) {
      SavedSearch ss =
          SavedSearch.builder()
              .userId(allUsers.get(i).getId())
              .name(searches[i][0])
              .intent(searches[i][1])
              .alertsEnabled(i % 2 == 0)
              .build();
      ss.setId(uuid("savedsearch:" + i));
      savedSearches.save(ss);
    }
  }

  private void seedReports(List<User> allUsers, List<Listing> allListings) {
    record Seed(ReportReason reason, String details) {}
    Seed[] seeds = {
      new Seed(ReportReason.SCAM, "Asked me to transfer a deposit before showing the flat."),
      new Seed(ReportReason.FAKE_LISTING, "Photos look copied from a hotel website."),
      new Seed(ReportReason.SPAM, "Same listing posted five times with different rents."),
    };
    for (int i = 0; i < seeds.length; i++) {
      Report r =
          Report.builder()
              .reporterId(allUsers.get(i + 20).getId())
              .reportedListingId(allListings.get(i * 7).getId())
              .reportedUserId(allListings.get(i * 7).getListerId())
              .reason(seeds[i].reason())
              .details(seeds[i].details())
              .build();
      r.setId(uuid("report:" + i));
      reports.save(r);
    }
  }

  // ---------- embeddings ----------

  private void embedListings(List<Listing> allListings, List<Locality> locs) {
    Map<UUID, String> localityNames = new java.util.HashMap<>();
    locs.forEach(l -> localityNames.put(l.getId(), l.getName()));
    Map<UUID, UUID> propertyLocality = new java.util.HashMap<>();
    properties.findAll().forEach(p -> propertyLocality.put(p.getId(), p.getLocalityId()));

    List<Listing> toEmbed = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (Listing l : allListings) {
      String locName =
          localityNames.getOrDefault(propertyLocality.get(l.getPropertyId()), "Mumbai");
      String text = EmbeddingTextComposer.composeListing(l, locName);
      String hash = EmbeddingTextComposer.sha256(text);
      if (!hash.equals(l.getEmbeddingTextHash())) {
        toEmbed.add(l);
        texts.add(text);
      }
    }
    if (toEmbed.isEmpty()) {
      log.info("Listing embeddings up to date");
      return;
    }
    List<float[]> vectors = embeddingProvider.embedBatch(texts);
    for (int i = 0; i < toEmbed.size(); i++) {
      vectorWriter.writeListingEmbedding(
          toEmbed.get(i).getId(), vectors.get(i), EmbeddingTextComposer.sha256(texts.get(i)));
    }
    log.info("Embedded {} listings", toEmbed.size());
  }

  private void embedFlatmates(List<FlatmateProfile> fms, List<Profile> allProfiles, List<Locality> locs) {
    Map<UUID, String> localityNames = new java.util.HashMap<>();
    locs.forEach(l -> localityNames.put(l.getId(), l.getName()));
    Map<UUID, Profile> profileByUser = new java.util.HashMap<>();
    allProfiles.forEach(p -> profileByUser.put(p.getUserId(), p));

    List<FlatmateProfile> toEmbed = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (FlatmateProfile fp : fms) {
      StringBuilder locNames = new StringBuilder();
      for (UUID lid : fp.getLocalityIds()) {
        if (!locNames.isEmpty()) {
          locNames.append(", ");
        }
        locNames.append(localityNames.getOrDefault(lid, ""));
      }
      String text =
          EmbeddingTextComposer.composeFlatmate(fp, profileByUser.get(fp.getUserId()), locNames.toString());
      String hash = EmbeddingTextComposer.sha256(text);
      if (!hash.equals(fp.getEmbeddingTextHash())) {
        toEmbed.add(fp);
        texts.add(text);
      }
    }
    if (toEmbed.isEmpty()) {
      log.info("Flatmate embeddings up to date");
      return;
    }
    List<float[]> vectors = embeddingProvider.embedBatch(texts);
    for (int i = 0; i < toEmbed.size(); i++) {
      vectorWriter.writeFlatmateEmbedding(
          toEmbed.get(i).getId(), vectors.get(i), EmbeddingTextComposer.sha256(texts.get(i)));
    }
    log.info("Embedded {} flatmate profiles", toEmbed.size());
  }
}
