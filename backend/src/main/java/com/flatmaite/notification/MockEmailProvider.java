package com.flatmaite.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Logs instead of sending — same contract, zero deliverability surprises in dev. */
@Component
@Slf4j
public class MockEmailProvider implements EmailProvider {

  @Override
  public void send(String toEmail, String subject, String body) {
    log.info("[mock email] to={} subject=\"{}\" body=\"{}\"", toEmail, subject, body);
  }
}
