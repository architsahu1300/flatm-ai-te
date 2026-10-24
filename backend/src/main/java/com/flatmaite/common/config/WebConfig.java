package com.flatmaite.common.config;

import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(FlatmaiteProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

  private final FlatmaiteProperties props;

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String dir = Paths.get(props.getStorage().getUploadDir()).toAbsolutePath().normalize().toString();
    // Disk first — real uploads live there. The classpath fallback carries the seeded
    // placeholder photos, which must survive hosts with an ephemeral filesystem: free
    // tiers wipe the disk on every redeploy, restart and idle spin-down, and the seeded
    // listing_images rows would then point at files that no longer exist.
    registry
        .addResourceHandler("/uploads/**")
        .addResourceLocations("file:" + dir + "/", "classpath:/seed-images/")
        .setCachePeriod(3600);
  }
}
