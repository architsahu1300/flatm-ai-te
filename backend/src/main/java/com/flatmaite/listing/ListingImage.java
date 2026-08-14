package com.flatmaite.listing;

import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "listing_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingImage extends BaseEntity {

  @Column(nullable = false)
  private String url;

  @Column(nullable = false)
  @Builder.Default
  private short sortOrder = 0;

  @Column(nullable = false)
  @Builder.Default
  private boolean isCover = false;

  private Integer width;
  private Integer height;
}
