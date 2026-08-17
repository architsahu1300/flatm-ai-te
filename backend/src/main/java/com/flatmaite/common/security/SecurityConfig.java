package com.flatmaite.common.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      ObjectProvider<AuthenticationSuccessHandler> googleSuccessHandler)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(
                        (req, res, e) -> writeJsonError(res, 401, "unauthorized", "Sign in required"))
                    .accessDeniedHandler(
                        (req, res, e) -> writeJsonError(res, 403, "forbidden", "Not allowed")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**", "/oauth2/**", "/login/oauth2/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/listings/**",
                        "/api/v1/flatmates/**",
                        "/api/v1/localities/**",
                        "/api/v1/amenities",
                        "/uploads/**")
                    .permitAll()
                    .requestMatchers("/api/v1/ai/**", "/api/v1/analytics/events")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    // Google OAuth wiring only exists when GOOGLE_CLIENT_ID is configured
    ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
    AuthenticationSuccessHandler successHandler = googleSuccessHandler.getIfAvailable();
    if (registrations != null && successHandler != null) {
      http.oauth2Login(
          oauth ->
              oauth
                  .clientRegistrationRepository(registrations)
                  .successHandler(successHandler));
    }
    return http.build();
  }

  private static void writeJsonError(
      HttpServletResponse res, int status, String code, String message) throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter()
        .write("{\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}".formatted(code, message));
  }
}
