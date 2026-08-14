package com.flatmaite.auth;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.security.JwtService;
import com.flatmaite.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Google OAuth is strictly additive: with no GOOGLE_CLIENT_ID env the beans don't exist, the
 * /providers endpoint reports google=false, and the frontend hides the button.
 */
@Configuration
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${flatmaite.google.client-id:}')")
@RequiredArgsConstructor
public class GoogleOAuthConfig {

  @Bean
  public ClientRegistrationRepository clientRegistrationRepository(FlatmaiteProperties props) {
    ClientRegistration google =
        CommonOAuth2Provider.GOOGLE
            .getBuilder("google")
            .clientId(props.getGoogle().getClientId())
            .clientSecret(props.getGoogle().getClientSecret())
            .build();
    return new InMemoryClientRegistrationRepository(google);
  }

  @Bean
  public AuthenticationSuccessHandler googleSuccessHandler(
      AuthService authService, JwtService jwtService, FlatmaiteProperties props) {
    return (request, response, authentication) -> {
      OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
      String email = oauthUser.getAttribute("email");
      String name = oauthUser.getAttribute("name");
      String picture = oauthUser.getAttribute("picture");
      User user = authService.loginByGoogle(email, name, picture);
      jwtService.writeCookie(
          response, jwtService.issue(user.getId(), user.getRole(), user.getName()));
      response.sendRedirect(props.getFrontendUrl() + "/onboarding");
    };
  }
}
