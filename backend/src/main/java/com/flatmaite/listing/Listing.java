package com.flatmaite.listing;

import com.flatmaite.common.domain.Diet;
import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.GenderPreference;
import com.flatmaite.common.domain.ListingStatus;
import com.flatmaite.common.domain.ListingType;
import com.flatmaite.common.domain.RoomType;
import com.flatmaite.common.domain.SocialStyle;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Central search entity. The {@code embedding vector(1536)} and generated {@code search_tsv}
 * columns are deliberately absent here — they are read/written only via JdbcTemplate in the search
 * layer.
 */
@Entity
@Table(name = "listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Listing extends BaseEntity {

  private UUID propertyId;

  @Column(nullable = false)
  private UUID listerId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  private ListingType type;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private ListingStatus status = ListingStatus.DRAFT;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  @Builder.Default
  private String description = "";

  @Column(nullable = false)
  private int rentMonthly;

  @Column(nullable = false)
  @Builder.Default
  private int deposit = 0;

  @Column(nullable = false)
  @Builder.Default
  private int maintenanceMonthly = 0;

  @Column(nullable = false)
  private LocalDate availableFrom;

  @Column(nullable = false)
  @Builder.Default
  private short minLeaseMonths = 11;

  private Short maxOccupants;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  private RoomType roomType;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private Furnishing furnishing = Furnishing.UNFURNISHED;

  private Boolean bathroomAttached;
  private Boolean balcony;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private GenderPreference preferredGender = GenderPreference.ANY;

  @Column(nullable = false)
  @Builder.Default
  private boolean couplesAllowed = false;

  private Boolean householdSmoking;
  private Boolean householdPets;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private Diet householdDiet;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private SocialStyle householdSocial;

  private String occupantsDesc;

  private String embeddingTextHash;

  @Column(nullable = false)
  @Builder.Default
  private float qualityScore = 0f;

  @Column(nullable = false)
  @Builder.Default
  private int viewCount = 0;

  @Column(nullable = false)
  @Builder.Default
  private boolean isBoosted = false;

  private Instant boostedUntil;

  @Column(nullable = false)
  @Builder.Default
  private float scamRiskScore = 0f;

  private Instant expiresAt;
  private Instant deletedAt;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "listing_id", nullable = false)
  @OrderBy("sortOrder ASC")
  @Builder.Default
  private List<ListingImage> images = new ArrayList<>();

  @ManyToMany
  @JoinTable(
      name = "listing_amenities",
      joinColumns = @JoinColumn(name = "listing_id"),
      inverseJoinColumns = @JoinColumn(name = "amenity_id"))
  @Builder.Default
  private Set<Amenity> amenities = new LinkedHashSet<>();
}
