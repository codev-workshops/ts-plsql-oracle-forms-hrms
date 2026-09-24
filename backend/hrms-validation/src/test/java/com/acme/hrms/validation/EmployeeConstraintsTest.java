package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.validation.constraints.HireDateWithinLimit;
import com.acme.hrms.validation.constraints.HireDateWithinLimitValidator;
import com.acme.hrms.validation.constraints.SsnValidator;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Runtime behaviour of the P3 custom constraints (VAL-01 hire-date limit, @Ssn, phone rule). */
class EmployeeConstraintsTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void ssnAcceptsNineDigitsWithOptionalDashesAndRejectsZeroGroups() {
    SsnValidator v = new SsnValidator();
    assertThat(v.isValid(null, null)).isTrue();
    assertThat(v.isValid("123-45-6789", null)).isTrue();
    assertThat(v.isValid("123456789", null)).isTrue();
    assertThat(v.isValid("123-456789", null)).isTrue();
    assertThat(v.isValid("12345678", null)).isFalse();
    assertThat(v.isValid("123 45 6789", null)).isFalse();
    assertThat(v.isValid("000-45-6789", null)).isFalse();
    assertThat(v.isValid("123-00-6789", null)).isFalse();
    assertThat(v.isValid("123-45-0000", null)).isFalse();
  }

  @Test
  void hireDateLimitIsInclusiveOfTheConfiguredDay() throws Exception {
    Clock fixed = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
    HireDateWithinLimitValidator v = newValidator(fixed);
    LocalDate today = LocalDate.of(2026, 1, 15);
    assertThat(v.isValid(null, null)).isTrue();
    assertThat(v.isValid(today.minusYears(30), null)).isTrue();
    assertThat(v.isValid(today.plusDays(HireDateWithinLimit.DEFAULT_MAX_FUTURE_DAYS), null))
        .isTrue();
    assertThat(v.isValid(today.plusDays(HireDateWithinLimit.DEFAULT_MAX_FUTURE_DAYS + 1), null))
        .isFalse();
  }

  @Test
  void createRequestAppliesServerRulesNotPllRules() {
    EmployeeCreateRequest r = minimalValid();
    assertThat(violations(r)).isEmpty();

    r.setEmail("first.last@mail.sub.example.com");
    assertThat(violations(r)).as("VAL-02: sub-domains are valid on the server").isEmpty();
    r.setEmail("not-an-email");
    assertThat(violations(r)).extracting(p -> p.getPropertyPath().toString()).contains("email");

    r = minimalValid();
    r.setPhoneWork("+1 (555) 123-4567");
    assertThat(violations(r)).as("11 digits after stripping").isEmpty();
    r.setPhoneWork("555-1234");
    assertThat(violations(r)).extracting(p -> p.getPropertyPath().toString()).contains("phoneWork");

    r = minimalValid();
    r.setSsn("123-45-6789");
    assertThat(violations(r)).isEmpty();
    r.setSsn("000-45-6789");
    assertThat(violations(r)).extracting(p -> p.getPropertyPath().toString()).contains("ssn");

    r = minimalValid();
    r.setFirstName("   ");
    assertThat(violations(r))
        .as("-20010: blank names are trimmed to null and rejected")
        .extracting(p -> p.getPropertyPath().toString())
        .contains("firstName");
  }

  private static Set<ConstraintViolation<EmployeeCreateRequest>> violations(
      EmployeeCreateRequest r) {
    return VALIDATOR.validate(r);
  }

  private static EmployeeCreateRequest minimalValid() {
    EmployeeCreateRequest r = new EmployeeCreateRequest();
    r.setFirstName("Ada");
    r.setLastName("Lovelace");
    r.setHireDate(LocalDate.now().plusDays(1));
    r.setDeptId(10);
    r.setJobId(20);
    r.setEmploymentType("FULL_TIME");
    return r;
  }

  private static HireDateWithinLimitValidator newValidator(Clock clock) throws Exception {
    HireDateWithinLimitValidator v = new HireDateWithinLimitValidator(clock);
    v.initialize(
        EmployeeCreateRequest.class
            .getDeclaredField("hireDate")
            .getAnnotation(HireDateWithinLimit.class));
    return v;
  }
}
