package com.flatmaite.notification;

/** Outbound email seam — mock in dev, SES/Sendgrid/etc. in production. */
public interface EmailProvider {

  void send(String toEmail, String subject, String body);
}
