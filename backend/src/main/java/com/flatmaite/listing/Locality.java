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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "localities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Locality extends BaseEntity {

  @Column(nullable = false, unique = true)
  private String name;

  @Column(nullable = false)
  @Builder.Default
  private String city = "Mumbai";

  @Column(nullable = false)
  private double lat;

  @Column(nullable = false)
  private double lng;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Builder.Default
  private String[] aliases = new String[0];
}
