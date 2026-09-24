package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmergencyContactRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTerminateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTransferRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import com.acme.hrms.validation.dto.employee.StrictRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StrictRequestTest {

  /** Spring Boot's mapper is lenient; the P3 DTOs must capture unknowns without failing binding. */
  private final ObjectMapper lenient =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void unknownPropertiesAreCapturedWhateverTheirValueAndRejectedOnDemand() throws Exception {
    EmployeeUpdateRequest body =
        lenient.readValue(
            "{\"firstName\":\"A\",\"lastName\":\"B\",\"hireDate\":null,\"employmentStatus\":\"X\"}",
            EmployeeUpdateRequest.class);
    assertThat(body.getFirstName()).isEqualTo("A");
    assertThat(body.unknownProperties()).containsExactly("hireDate", "employmentStatus");
    assertThatThrownBy(body::requireNoUnknownProperties)
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.field()).isEqualTo("hireDate");
              assertThat(e.details())
                  .extracting(d -> d.field() + ":" + d.code())
                  .containsExactly("hireDate:UnknownProperty", "employmentStatus:UnknownProperty");
            });
  }

  @Test
  void knownPropertiesAreNotUnknown() throws Exception {
    EmployeeUpdateRequest body =
        lenient.readValue("{\"firstName\":\"A\",\"middleName\":null}", EmployeeUpdateRequest.class);
    assertThat(body.unknownProperties()).isEmpty();
    assertThatCode(body::requireNoUnknownProperties).doesNotThrowAnyException();
  }

  @Test
  void moneyBindsOnlyTwoDecimalStringsAndDefersEverythingElse() throws Exception {
    SalaryChangeRequest ok =
        lenient.readValue("{\"baseSalary\":\"-1.00\"}", SalaryChangeRequest.class);
    assertThat(ok.getBaseSalary()).isEqualByComparingTo("-1.00");
    assertThat(ok.malformedProperties()).isEmpty();
    assertThatCode(ok::requireNoMalformedProperties).doesNotThrowAnyException();

    SalaryChangeRequest absent =
        lenient.readValue("{\"baseSalary\":null}", SalaryChangeRequest.class);
    assertThat(absent.getBaseSalary()).isNull();
    assertThat(absent.malformedProperties()).isEmpty();

    for (String wire :
        new String[] {
          "110000", "110000.5", "\"110000\"", "\"110000.0\"", "\"1.000\"", "{}", "[]"
        }) {
      EmployeeCreateRequest body =
          lenient.readValue(
              "{\"firstName\":\"A\",\"initialSalary\":" + wire + ",\"lastName\":\"B\"}",
              EmployeeCreateRequest.class);
      assertThat(body.getInitialSalary()).as(wire).isNull();
      assertThat(body.getLastName()).as(wire).isEqualTo("B");
      assertThat(body.unknownProperties()).as(wire).isEmpty();
      assertThat(body.malformedProperties()).as(wire).containsExactly("initialSalary");
      assertThatThrownBy(body::requireNoMalformedProperties)
          .isInstanceOfSatisfying(
              HrmsException.class,
              e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(e.field()).isEqualTo("initialSalary");
                assertThat(e.details())
                    .extracting(d -> d.field() + ":" + d.code())
                    .containsExactly("initialSalary:InvalidFormat");
              });
    }
  }

  @Test
  void everyP3RequestBodyIsStrict() {
    assertThat(EmployeeCreateRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(EmployeeUpdateRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(EmployeeTerminateRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(EmployeeTransferRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(SalaryChangeRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(DependentRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(EmergencyContactRequest.class).isAssignableTo(StrictRequest.class);
  }
}
