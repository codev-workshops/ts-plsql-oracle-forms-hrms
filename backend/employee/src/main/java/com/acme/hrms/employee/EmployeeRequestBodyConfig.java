package com.acme.hrms.employee;

import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The P3 request schemas ({@code contracts/p3-employee/openapi.yaml}, every DTO in {@code
 * com.acme.hrms.validation.dto.employee}) are {@code additionalProperties: false}, so those bodies
 * are read with {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} (rendered as {@code 400
 * VALIDATION_FAILED} by the GlobalExceptionHandler). Request bodies of other modules and the shared
 * {@code ObjectMapper} bean are untouched.
 */
@Configuration
public class EmployeeRequestBodyConfig implements WebMvcConfigurer {

  static final String STRICT_DTO_PACKAGE = EmployeeUpdateRequest.class.getPackageName();

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (HttpMessageConverter<?> converter : converters) {
      if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
        converters.add(0, new StrictP3RequestConverter(jackson.getObjectMapper()));
        return;
      }
    }
  }

  static final class StrictP3RequestConverter extends MappingJackson2HttpMessageConverter {

    StrictP3RequestConverter(ObjectMapper lenient) {
      super(lenient.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
      return type instanceof Class<?> c
          && c.getPackageName().equals(STRICT_DTO_PACKAGE)
          && super.canRead(type, contextClass, mediaType);
    }

    @Override
    protected boolean canWrite(MediaType mediaType) {
      return false;
    }
  }
}
