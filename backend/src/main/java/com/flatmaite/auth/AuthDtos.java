package com.flatmaite.auth;

import com.flatmaite.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {}

  public record RegisterRequest(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Email @Size(max = 255) String email,
      @NotBlank @Size(min = 8, max = 100) String password) {}

  public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

  public record OtpRequest(@NotBlank @Pattern(regexp = "[6-9]\\d{9}") String phone) {}

  public record OtpVerifyRequest(
      @NotBlank @Pattern(regexp = "[6-9]\\d{9}") String phone,
      @NotBlank @Pattern(regexp = "\\d{6}") String otp,
      @Size(max = 120) String name) {}

  public record SessionResponse(
      UUID id, String name, String email, String phone, String role, boolean onboarded) {

    public static SessionResponse of(User user, boolean onboarded) {
      return new SessionResponse(
          user.getId(),
          user.getName(),
          user.getEmail(),
          user.getPhone(),
          user.getRole().name(),
          onboarded);
    }
  }
}
