package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.employee.EmployeeListQuery;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** {@code hireDateFrom > hireDateTo} reports on {@code hireDateTo} (p3-employee openapi.yaml). */
class EmployeeListQueryTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void reversedRangeIsValidationFailedOnHireDateTo() {
    EmployeeListQuery q = query(LocalDate.of(2025, 6, 30), LocalDate.of(2025, 6, 1));
    assertThat(VALIDATOR.validate(q)).as("no Bean Validation violation carries the rule").isEmpty();
    assertThatThrownBy(q::requireHireDateRange)
        .isInstanceOfSatisfying(
            HrmsException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.getMessage()).isEqualTo("Request validation failed");
              assertThat(e.field()).isEqualTo("hireDateTo");
              assertThat(e.details())
                  .extracting(d -> d.field() + ":" + d.code() + ":" + d.message())
                  .containsExactly(
                      "hireDateTo:HireDateRange:hireDateFrom must be before or equal to hireDateTo");
            });
  }

  @Test
  void orderedEqualOrOpenRangesPass() {
    LocalDate d = LocalDate.of(2025, 6, 1);
    for (EmployeeListQuery q :
        new EmployeeListQuery[] {
          query(d, d.plusDays(29)), query(d, d), query(d, null), query(null, d), query(null, null)
        }) {
      assertThat(VALIDATOR.validate(q)).isEmpty();
      assertThatCode(q::requireHireDateRange).doesNotThrowAnyException();
    }
  }

  @Test
  void otherQueryConstraintsStillApply() {
    EmployeeListQuery q = query(null, null);
    q.setStatus("RETIRED");
    q.setSize(101);
    q.setPage(-1);
    assertThat(VALIDATOR.validate(q))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("status", "size", "page");
  }

  private static EmployeeListQuery query(LocalDate from, LocalDate to) {
    EmployeeListQuery q = new EmployeeListQuery();
    q.setHireDateFrom(from);
    q.setHireDateTo(to);
    return q;
  }
}
