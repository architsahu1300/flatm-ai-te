package com.flatmaite.auth.otp;

import com.flatmaite.common.config.FlatmaiteProperties;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Chooses the OTP provider from {@code FM_OTP_PROVIDER}, mirroring how
 * {@link com.flatmaite.ai.AiProviderConfig} selects LLM providers.
 *
 * <p>An unrecognised name, or Message Central without its credentials, fails startup rather than
 * falling back to the mock. A deployment that asked for real SMS and silently logged codes instead
 * would lock every phone user out with no error anywhere.
 */
@Configuration
@Slf4j
public class OtpProviderConfig {

  @Bean
  public OtpProvider otpProvider(FlatmaiteProperties props, RestClient.Builder http) {
    String configured = props.getOtp().getProvider();
    String provider = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);

    if (MockOtpProvider.NAME.equals(provider)) {
      log.info("OTP provider: MOCK (codes go to the backend log, no SMS is sent)");
      return new MockOtpProvider();
    }
    if (MessageCentralOtpProvider.NAME.equals(provider)) {
      FlatmaiteProperties.MessageCentral config = props.getOtp().getMessageCentral();
      if (isBlank(config.getCustomerId()) || isBlank(config.getPassword())) {
        throw new IllegalStateException(
            "FM_OTP_PROVIDER=message-central needs MESSAGE_CENTRAL_CUSTOMER_ID and"
                + " MESSAGE_CENTRAL_PASSWORD");
      }
      log.info("OTP provider: MESSAGE CENTRAL (customer {})", config.getCustomerId());
      return new MessageCentralOtpProvider(http, config);
    }
    throw new IllegalStateException(
        "Unsupported FM_OTP_PROVIDER '" + configured + "'. Supported: mock, message-central.");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
