package com.flatmaite.common.security;

import com.flatmaite.common.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

  private CurrentUser() {}

  /** The authenticated principal, or null for anonymous requests. */
  public static AuthPrincipal orNull() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
      return principal;
    }
    return null;
  }

  /** The authenticated principal, or 401. */
  public static AuthPrincipal require() {
    AuthPrincipal principal = orNull();
    if (principal == null) {
      throw ApiException.unauthorized("Sign in required");
    }
    return principal;
  }
}
