package com.flatmaite.auth.otp;

/**
 * Sends and checks one-time sign-in codes for Indian mobile numbers (ten digits, no +91). Rate
 * limits and the daily send cap sit above this, in {@link com.flatmaite.auth.OtpService} and
 * {@link com.flatmaite.auth.AuthController}.
 */
public interface OtpProvider {

  /** Sends a fresh code to {@code phone}; throws {@code ApiException} when it cannot. */
  void send(String phone);

  /**
   * True only when {@code code} matches the latest code sent to {@code phone} and is still live; a
   * matched code is spent. A wrong or stale code is {@code false}. A provider outage throws instead,
   * so nobody is told their code was wrong when it was never checked.
   */
  boolean verify(String phone, String code);

  /** Recorded against every send, so the daily count shows which provider spent what. */
  String name();

  /** Shown under the code box after a send, or null for nothing. */
  default String note() {
    return null;
  }
}
