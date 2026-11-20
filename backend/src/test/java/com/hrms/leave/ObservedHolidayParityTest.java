package com.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DEF-02 decision (P8, OQ-04 "fix"): observed holidays are fixed through data, not code. A holiday on a
 * weekend gets an observed-date row (the data-migration precheck reports weekend holidays without one); the
 * exact-date match of PKG_LEAVE.calculate_business_days is kept.
 */
class ObservedHolidayParityTest extends LeaveTestSupport {

    @Autowired
    BusinessDays businessDays;

    @Test
    void anObservedDateRowExcludesTheWeekdayItIsObservedOn() throws Exception {
        jdbc.update("""
                INSERT INTO hrms.holidays (holiday_name, holiday_date, location_code, active_flag, created_by)
                VALUES ('Independence Day', DATE '2026-07-04', NULL, true, 'test'),
                       ('Independence Day (observed)', DATE '2026-07-03', NULL, false, 'test')
                """);
        LocalDate thu = LocalDate.of(2026, 7, 2);
        LocalDate mon = LocalDate.of(2026, 7, 6);
        // Legacy behaviour (no observed row): the Saturday holiday removes nothing; Thu, Fri, Mon count
        assertThat(businessDays.count(thu, mon, "CHI")).isEqualTo(3);
        jdbc.update("UPDATE hrms.holidays SET active_flag = true WHERE holiday_name = 'Independence Day (observed)'");
        assertThat(businessDays.count(thu, mon, "CHI")).isEqualTo(2);
        submit(tokenFor(37), days(PTO, "2026-07-02", "2026-07-06")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDays").value(2.0));
    }
}
