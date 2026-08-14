package com.flatmaite.common.security;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey key;
  private final FlatmaiteProperties props;

  public JwtService(FlatmaiteProperties props) {
    this.props = props;
    this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
  }

  public String issue(UUID userId, UserRole role, String name) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("role", role.name())
        .claim("name", name)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(Duration.ofHours(props.getJwt().getTtlHours()))))
        .signWith(key)
        .compact();
  }

  /** Returns null on any parse/expiry failure — callers treat null as unauthenticated. */
  public AuthPrincipal parse(String token) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      return new AuthPrincipal(
          UUID.fromString(claims.getSubject()),
          UserRole.valueOf(claims.get("role", String.class)),
          claims.get("name", String.class));
    } catch (Exception e) {
      return null;
    }
  }

  public void writeCookie(HttpServletResponse response, String token) {
    Cookie cookie = new Cookie(props.getJwt().getCookieName(), token);
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(props.getJwt().getTtlHours() * 3600);
    cookie.setAttribute("SameSite", "Lax");
    response.addCookie(cookie);
  }

  public void clearCookie(HttpServletResponse response) {
    Cookie cookie = new Cookie(props.getJwt().getCookieName(), "");
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(0);
    cookie.setAttribute("SameSite", "Lax");
    response.addCookie(cookie);
  }

  public String cookieName() {
    return props.getJwt().getCookieName();
  }
}
