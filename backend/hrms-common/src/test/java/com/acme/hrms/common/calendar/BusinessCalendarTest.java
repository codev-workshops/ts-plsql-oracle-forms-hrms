package com.acme.hrms.common.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.common.calendar.BusinessCalendar.Holiday;
import com.acme.hrms.common.calendar.BusinessCalendar.Result;
import com.acme.hrms.common.testsupport.HrmsPostgres;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 for {@code PKG_LEAVE.calculate_business_days} on PostgreSQL: weekend exclusion, global
 * and location-scoped holidays, inactive holidays, and the BUG-05 observed-date shift (Sat -> Fri,
 * Sun -> Mon) that the legacy loop did not apply.
 */
class BusinessCalendarTest {

  private static JdbcTemplate jdbc;
  private static BusinessCalendar calendar;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixture(jdbc, "01_reference_data.sql");
    calendar = new BusinessCalendar(jdbc);
  }

  private static Holiday h(String name, LocalDate d) {
    return new Holiday(name, d, BusinessCalendar.observedDate(d));
  }

  @Test
  void weekendsAreNeverBusinessDays() {
    Result r =
        BusinessCalendar.count(LocalDate.of(2024, 7, 8), LocalDate.of(2024, 7, 14), List.of());
    assertThat(r.businessDays()).isEqualTo(5);
    assertThat(
            BusinessCalendar.count(LocalDate.of(2024, 7, 13), LocalDate.of(2024, 7, 14), List.of())
                .businessDays())
        .isZero();
    assertThat(
            BusinessCalendar.count(LocalDate.of(2024, 7, 9), LocalDate.of(2024, 7, 8), List.of())
                .businessDays())
        .isZero();
  }

  @Test
  void weekdayHolidayIsExcludedAndReportedOnce() {
    Result r =
        BusinessCalendar.count(
            LocalDate.of(2024, 7, 1),
            LocalDate.of(2024, 7, 5),
            List.of(h("Independence Day", LocalDate.of(2024, 7, 4))));
    assertThat(r.businessDays()).isEqualTo(4);
    assertThat(r.holidays()).hasSize(1);
    assertThat(r.holidays().get(0).observedDate()).isEqualTo(LocalDate.of(2024, 7, 4));
  }

  @Test
  void saturdayHolidayIsObservedOnFriday() {
    // 2027-12-25 is a Saturday -> observed Friday 2027-12-24
    Result r =
        BusinessCalendar.count(
            LocalDate.of(2027, 12, 20),
            LocalDate.of(2027, 12, 24),
            List.of(h("Christmas Day", LocalDate.of(2027, 12, 25))));
    assertThat(r.businessDays()).isEqualTo(4);
    assertThat(r.holidays()).hasSize(1);
    assertThat(r.holidays().get(0).holidayDate()).isEqualTo(LocalDate.of(2027, 12, 25));
    assertThat(r.holidays().get(0).observedDate()).isEqualTo(LocalDate.of(2027, 12, 24));
  }

  @Test
  void sundayHolidayIsObservedOnMonday() {
    // 2028-12-24 Sunday -> Monday 2028-12-25 (also a holiday itself: reported once, counted once)
    Result r =
        BusinessCalendar.count(
            LocalDate.of(2028, 12, 25),
            LocalDate.of(2028, 12, 29),
            List.of(
                h("Christmas Eve", LocalDate.of(2028, 12, 24)),
                h("Christmas Day", LocalDate.of(2028, 12, 25))));
    assertThat(r.businessDays()).isEqualTo(4);
    assertThat(r.holidays()).hasSize(1);
    assertThat(r.holidays().get(0).observedDate()).isEqualTo(LocalDate.of(2028, 12, 25));
  }

  @Test
  void observedDateOutsideRangeDoesNotCount() {
    // Sat 2027-12-25 observed Fri 24 – range starting Mon 27 is unaffected
    Result r =
        BusinessCalendar.count(
            LocalDate.of(2027, 12, 27),
            LocalDate.of(2027, 12, 31),
            List.of(h("Christmas Day", LocalDate.of(2027, 12, 25))));
    assertThat(r.businessDays()).isEqualTo(5);
    assertThat(r.holidays()).isEmpty();
  }

  @Test
  void databaseHolidaysGlobalLocationAndInactive() {
    jdbc.update(
        "insert into holidays (holiday_id, holiday_name, holiday_date, location_code, active_flag,"
            + " created_by) values (901, 'NYC Only', date '2024-07-02', 'NYC', 'Y', 't'),"
            + " (902, 'Inactive', date '2024-07-03', null, 'N', 't'),"
            + " (903, 'Sat holiday', date '2024-07-06', null, 'Y', 't')");
    // seed: Independence Day Thu 2024-07-04 (global). Range Mon 1 .. Fri 5.
    Result hq = calendar.businessDays(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 5), "HQ");
    assertThat(hq.businessDays()).isEqualTo(3); // Jul 4 + Sat-6 observed Fri-5
    assertThat(hq.holidays())
        .extracting(Holiday::observedDate)
        .containsExactly(LocalDate.of(2024, 7, 4), LocalDate.of(2024, 7, 5));

    Result nyc = calendar.businessDays(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 5), "NYC");
    assertThat(nyc.businessDays()).isEqualTo(2);

    assertThat(calendar.isBusinessDay(LocalDate.of(2024, 7, 3), null)).isTrue();
    assertThat(calendar.isBusinessDay(LocalDate.of(2024, 7, 5), null)).isFalse();
    assertThat(calendar.isBusinessDay(LocalDate.of(2024, 7, 6), null)).isFalse();
    assertThat(
            calendar.countBusinessDays(
                LocalDate.of(2024, 12, 23), LocalDate.of(2024, 12, 27), "HQ"))
        .isEqualTo(3); // Christmas Eve + Christmas Day
  }
}
