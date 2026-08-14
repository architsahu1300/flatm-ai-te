package com.flatmaite.common.security;

import com.flatmaite.common.domain.UserRole;
import java.util.UUID;

public record AuthPrincipal(UUID userId, UserRole role, String name) {

  public boolean isAdmin() {
    return role == UserRole.ADMIN;
  }
}
