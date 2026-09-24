package com.acme.hrms.common.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code PKG_LEAVE.calculate_business_days} on PostgreSQL: weekends and active {@code holidays}
 * (global rows or rows for the given location) are excluded. BUG-05 (contracts/p2-leave): a holiday
 * dated on Saturday is observed on the preceding Friday, one dated on Sunday on the following
 * Monday, and the observed date is the one excluded. {@code holidays} is reference data owned by
 * P0; this is a read-only view of it.
 */
@Component
public class BusinessCalendar {

  /** One excluded holiday of a range (the {@code BusinessDays.holidays[]} wire item). */
  public record Holiday(String holidayName, LocalDate holidayDate, LocalDate observedDate) {}

  public record Result(LocalDate start, LocalDate end, int businessDays, List<Holiday> holidays) {}

  private final JdbcTemplate jdbc;

  public BusinessCalendar(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Result businessDays(LocalDate start, LocalDate end, @Nullable String locationCode) {
    if (start.isAfter(end)) {
      return new Result(start, end, 0, List.of());
    }
    // observed dates can move a weekend holiday one day outside the range, so read +-1 day
    List<Holiday> candidates =
        jdbc.query(
            "select holiday_name, holiday_date from holidays where active_flag = 'Y'"
                + " and holiday_date between ? and ?"
                + " and (location_code is null or location_code = ?)"
                + " order by holiday_date, holiday_id",
            (rs, i) -> {
              LocalDate d = rs.getObject("holiday_date", LocalDate.class);
              return new Holiday(rs.getString("holiday_name"), d, observedDate(d));
            },
            start.minusDays(1),
            end.plusDays(1),
            locationCode);
    return count(start, end, candidates);
  }

  public int countBusinessDays(LocalDate start, LocalDate end, @Nullable String locationCode) {
    return businessDays(start, end, locationCode).businessDays();
  }

  /** Whether a single date is a business day (weekday and not an observed holiday). */
  public boolean isBusinessDay(LocalDate date, @Nullable String locationCode) {
    return countBusinessDays(date, date, locationCode) == 1;
  }

  /** Sat -> preceding Fri, Sun -> following Mon, otherwise the date itself (BUG-05). */
  public static LocalDate observedDate(LocalDate holidayDate) {
    return switch (holidayDate.getDayOfWeek()) {
      case SATURDAY -> holidayDate.minusDays(1);
      case SUNDAY -> holidayDate.plusDays(1);
      default -> holidayDate;
    };
  }

  /** Pure computation over already-loaded holidays (unit-testable without a database). */
  public static Result count(LocalDate start, LocalDate end, List<Holiday> holidays) {
    if (start.isAfter(end)) {
      return new Result(start, end, 0, List.of());
    }
    Map<LocalDate, Holiday> observed = new LinkedHashMap<>();
    for (Holiday h : holidays) {
      LocalDate o = h.observedDate();
      if (!o.isBefore(start) && !o.isAfter(end)) {
        observed.putIfAbsent(o, h);
      }
    }
    int n = 0;
    List<Holiday> excluded = new ArrayList<>();
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
      if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
        continue;
      }
      Holiday h = observed.get(d);
      if (h != null) {
        excluded.add(h);
        continue;
      }
      n++;
    }
    return new Result(start, end, n, List.copyOf(excluded));
  }
}
