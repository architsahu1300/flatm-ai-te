package com.flatmaite.auth;

import com.flatmaite.common.web.ApiException;
import com.flatmaite.user.Profile;
import com.flatmaite.user.ProfileRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository users;
  private final ProfileRepository profiles;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public User register(String name, String email, String rawPassword) {
    String normalizedEmail = email.toLowerCase(Locale.ROOT).trim();
    if (users.existsByEmailIgnoreCase(normalizedEmail)) {
      throw ApiException.conflict("email_taken", "An account with this email already exists");
    }
    User user =
        User.builder()
            .name(name.trim())
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(rawPassword))
            .build();
    user = users.save(user);
    ensureProfile(user.getId());
    return user;
  }

  @Transactional(readOnly = true)
  public User login(String email, String rawPassword) {
    User user =
        users
            .findByEmailIgnoreCase(email.toLowerCase(Locale.ROOT).trim())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
    if (user.isSuspended()) {
      throw ApiException.forbidden("This account is suspended");
    }
    if (user.getPasswordHash() == null
        || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      throw ApiException.unauthorized("Invalid email or password");
    }
    return user;
  }

  /** Phone-OTP path: find or create the user by phone. */
  @Transactional
  public User loginByPhone(String phone, String name) {
    User user =
        users
            .findByPhone(phone)
            .orElseGet(
                () ->
                    users.save(
                        User.builder()
                            .name(name == null || name.isBlank() ? "New user" : name.trim())
                            .phone(phone)
                            .build()));
    if (user.isSuspended()) {
      throw ApiException.forbidden("This account is suspended");
    }
    user.setPhoneVerifiedAt(Instant.now());
    ensureProfile(user.getId());
    return user;
  }

  /** Google OAuth path: find or create by verified email. */
  @Transactional
  public User loginByGoogle(String email, String name, String pictureUrl) {
    String normalizedEmail = email.toLowerCase(Locale.ROOT).trim();
    User user =
        users
            .findByEmailIgnoreCase(normalizedEmail)
            .orElseGet(
                () ->
                    users.save(
                        User.builder()
                            .name(name == null ? normalizedEmail : name)
                            .email(normalizedEmail)
                            .image(pictureUrl)
                            .build()));
    if (user.isSuspended()) {
      throw ApiException.forbidden("This account is suspended");
    }
    if (user.getEmailVerifiedAt() == null) {
      user.setEmailVerifiedAt(Instant.now());
    }
    ensureProfile(user.getId());
    return user;
  }

  public boolean isOnboarded(UUID userId) {
    return profiles
        .findByUserId(userId)
        .map(p -> p.getProfileCompleteness() > 0)
        .orElse(false);
  }

  private void ensureProfile(UUID userId) {
    if (profiles.findByUserId(userId).isEmpty()) {
      profiles.save(Profile.builder().userId(userId).build());
    }
  }
}
