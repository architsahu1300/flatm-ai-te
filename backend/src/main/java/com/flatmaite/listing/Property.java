package com.flatmaite.listing;

import com.flatmaite.common.domain.PropertyType;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "properties")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Property extends BaseEntity {

  @Column(nullable = false)
  private UUID ownerId;

  @Column(nullable = false)
  private UUID localityId;

  @Column(nullable = false)
  private String addressLine;

  private String societyName;
  private String pincode;
  private Double lat;
  private Double lng;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private PropertyType propertyType = PropertyType.APARTMENT;

  @Column(nullable = false)
  private short bhk;

  private Short totalBathrooms;
  private Short floorNumber;
  private Short totalFloors;
  private Integer builtUpSqft;
  private Short ageYears;

  @Column(nullable = false)
  @Builder.Default
  private boolean isVerified = false;
}
