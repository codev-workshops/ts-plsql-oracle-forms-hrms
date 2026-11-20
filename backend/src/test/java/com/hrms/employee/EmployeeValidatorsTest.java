package com.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmployeeValidatorsTest {

    @Test
    void emailUsesTheServerRegexForEveryCaller() {
        assertThat(EmployeeValidators.isValidEmail(null)).isTrue();
        assertThat(EmployeeValidators.isValidEmail("a.b@mail.company.com")).isTrue(); // subdomains (DEF-04)
        assertThat(EmployeeValidators.isValidEmail("a@b.c")).isFalse(); // TLD of 2+ letters
        assertThat(EmployeeValidators.isValidEmail("a b@company.com")).isFalse();
        assertThat(EmployeeValidators.isValidEmail("@company.com")).isFalse();
    }

    @Test
    void phoneNeedsTenOrElevenDigits() {
        assertThat(EmployeeValidators.isValidPhone(null)).isTrue();
        assertThat(EmployeeValidators.isValidPhone("(312) 555-2108")).isTrue();
        assertThat(EmployeeValidators.isValidPhone("+1 312.555.2108")).isTrue();
        assertThat(EmployeeValidators.isValidPhone("555-2108")).isFalse();
    }

    @Test
    void ssnUsesTheStricterPllRule() {
        assertThat(EmployeeValidators.isValidSsn(null)).isTrue();
        assertThat(EmployeeValidators.isValidSsn("123-45-6789")).isTrue();
        assertThat(EmployeeValidators.isValidSsn("000-45-6789")).isFalse();
        assertThat(EmployeeValidators.isValidSsn("123-00-6789")).isFalse();
        assertThat(EmployeeValidators.isValidSsn("123-45-0000")).isFalse();
        assertThat(EmployeeValidators.isValidSsn("12345678")).isFalse();
    }

    @Test
    void dateNotFutureIsInclusiveOfToday() {
        LocalDate today = LocalDate.of(2026, 3, 2);
        assertThat(EmployeeValidators.isNotFuture(today, today)).isTrue();
        assertThat(EmployeeValidators.isNotFuture(today.plusDays(1), today)).isFalse();
    }

    @Test
    void terminatedToActiveOnlyThroughRehire() {
        assertThatThrownBy(() -> EmployeeCommandService.checkTransition("TERMINATED", "ACTIVE", false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.REACTIVATION_FORBIDDEN);
        EmployeeCommandService.checkTransition("TERMINATED", "ACTIVE", true);
        EmployeeCommandService.checkTransition("ACTIVE", "TERMINATED", false);
    }
}
