package com.acme.hrms.common.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every request schema in {@code contracts/*}/openapi.yaml is {@code additionalProperties: false},
 * so JSON request bodies are read with {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}
 * (rendered as {@code 400 VALIDATION_FAILED} by the GlobalExceptionHandler). Only the HTTP
 * converters are affected: the shared {@code ObjectMapper} bean used for persisted / recorded JSON
 * keeps its lenient defaults.
 */
@Configuration
public class StrictRequestBodyConfig implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (HttpMessageConverter<?> converter : converters) {
      if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
        jackson.setObjectMapper(
            jackson
                .getObjectMapper()
                .copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
      }
    }
  }
}
