package com.flatmaite.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flatmaite.common.config.FlatmaiteProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OtpProviderConfigTest {

  private final OtpProviderConfig config = new OtpProviderConfig();
  private final FlatmaiteProperties props = new FlatmaiteProperties();

  @Test
  void defaultsToTheMock() {
    assertThat(config.otpProvider(props, RestClient.builder())).isInstanceOf(MockOtpProvider.class);
  }

  @Test
  void messageCentral_withCredentials_isSelected() {
    props.getOtp().setProvider(" Message-Central ");
    props.getOtp().getMessageCentral().setCustomerId("C-42");
    props.getOtp().getMessageCentral().setPassword("secret");

    assertThat(config.otpProvider(props, RestClient.builder()))
        .isInstanceOf(MessageCentralOtpProvider.class);
  }

  @Test
  void messageCentral_withoutCredentials_refusesToStart() {
    props.getOtp().setProvider("message-central");

    assertThatThrownBy(() -> config.otpProvider(props, RestClient.builder()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MESSAGE_CENTRAL_CUSTOMER_ID")
        .hasMessageContaining("MESSAGE_CENTRAL_PASSWORD");
  }

  @Test
  void unknownProvider_refusesToStart() {
    props.getOtp().setProvider("twilio");

    assertThatThrownBy(() -> config.otpProvider(props, RestClient.builder()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FM_OTP_PROVIDER")
        .hasMessageContaining("twilio");
  }
}
