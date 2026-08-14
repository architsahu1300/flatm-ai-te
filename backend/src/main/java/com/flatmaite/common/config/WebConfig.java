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
    registry
        .addResourceHandler("/uploads/**")
        .addResourceLocations("file:" + dir + "/")
        .setCachePeriod(3600);
  }
}
