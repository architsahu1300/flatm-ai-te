package com.flatmaite.user;

import com.flatmaite.common.domain.UserRole;
import com.flatmaite.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

  @Column(unique = true)
  private String email;

  private Instant emailVerifiedAt;

  @Column(unique = true)
  private String phone;

  private Instant phoneVerifiedAt;

  private String passwordHash;

  @Column(nullable = false)
  private String name;

  private String image;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  @Builder.Default
  private UserRole role = UserRole.USER;

  @Column(nullable = false)
  @Builder.Default
  private boolean isSuspended = false;

  private Instant lastActiveAt;

  private Instant deletedAt;
}
