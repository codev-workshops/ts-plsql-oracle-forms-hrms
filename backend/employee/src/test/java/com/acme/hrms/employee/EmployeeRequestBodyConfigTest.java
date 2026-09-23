package com.acme.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.validation.dto.admin.DepartmentRequest;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import com.acme.hrms.validation.dto.leave.LeaveRequestCreateRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

class EmployeeRequestBodyConfigTest {

  private final ObjectMapper lenient =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private List<HttpMessageConverter<?>> converters() {
    List<HttpMessageConverter<?>> converters = new ArrayList<>();
    converters.add(new StringHttpMessageConverter());
    converters.add(new MappingJackson2HttpMessageConverter(lenient));
    new EmployeeRequestBodyConfig().extendMessageConverters(converters);
    return converters;
  }

  @Test
  void strictConverterIsFirstAndOnlyReadsP3EmployeeDtos() {
    List<HttpMessageConverter<?>> converters = converters();
    assertThat(converters).hasSize(3);
    HttpMessageConverter<?> strict = converters.get(0);
    assertThat(strict).isInstanceOf(EmployeeRequestBodyConfig.StrictP3RequestConverter.class);
    for (Class<?> dto :
        List.of(
            EmployeeCreateRequest.class,
            EmployeeUpdateRequest.class,
            DependentRequest.class,
            SalaryChangeRequest.class)) {
      assertThat(strict.canRead(dto, MediaType.APPLICATION_JSON)).as(dto.getSimpleName()).isTrue();
    }
    assertThat(strict.canRead(DepartmentRequest.class, MediaType.APPLICATION_JSON)).isFalse();
    assertThat(strict.canRead(LeaveRequestCreateRequest.class, MediaType.APPLICATION_JSON))
        .isFalse();
    assertThat(strict.canWrite(EmployeeUpdateRequest.class, MediaType.APPLICATION_JSON)).isFalse();
    assertThat(lenient.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
  }

  @Test
  void unknownPropertyIncludingNullFailsOnlyForP3Dtos() throws Exception {
    List<HttpMessageConverter<?>> converters = converters();
    MappingJackson2HttpMessageConverter strict =
        (MappingJackson2HttpMessageConverter) converters.get(0);
    MappingJackson2HttpMessageConverter plain =
        (MappingJackson2HttpMessageConverter) converters.get(2);
    String body = "{\"firstName\":\"A\",\"hireDate\":null}";

    assertThatThrownBy(() -> strict.read(EmployeeUpdateRequest.class, message(body)))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .cause()
        .isInstanceOf(UnrecognizedPropertyException.class)
        .hasFieldOrPropertyWithValue("propertyName", "hireDate");
    assertThat(strict.read(EmployeeUpdateRequest.class, message("{\"firstName\":\"A\"}")))
        .isInstanceOf(EmployeeUpdateRequest.class);
    assertThat(plain.read(DepartmentRequest.class, message("{\"unknown\":null}"))).isNotNull();
  }

  private static MockHttpInputMessage message(String body) {
    MockHttpInputMessage m = new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
    m.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return m;
  }
}
