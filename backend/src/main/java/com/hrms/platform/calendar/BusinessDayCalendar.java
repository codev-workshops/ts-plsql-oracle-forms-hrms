package com.hrms.platform.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Business-day calendar (legacy PKG_LEAVE.calculate_business_days / PKG_COMMON.is_business_day):
 * weekdays minus active holidays that are global (NULL location) or match the location.
 */
@Service
public class BusinessDayCalendar {

    private final JdbcTemplate jdbc;

    public BusinessDayCalendar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Holiday> holidays(LocalDate from, LocalDate to, String locationCode) {
        return jdbc.query("""
                SELECT holiday_id, holiday_date, holiday_name, location_code FROM holidays
                WHERE active_flag = 'Y' AND holiday_date BETWEEN ? AND ?
                  AND (location_code IS NULL OR ?::varchar IS NULL OR location_code = ?)
                ORDER BY holiday_date, holiday_id
                """, (rs, i) -> new Holiday(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                rs.getString(4)), from, to, locationCode, locationCode);
    }

    public int businessDays(LocalDate start, LocalDate end, String locationCode) {
        if (start.isAfter(end)) {
            return 0;
        }
        Set<LocalDate> holidayDates = new HashSet<>();
        jdbc.query("""
                SELECT holiday_date FROM holidays
                WHERE active_flag = 'Y' AND holiday_date BETWEEN ? AND ?
                  AND (location_code IS NULL OR location_code = ?)
                """, rs -> {
            holidayDates.add(rs.getObject(1, LocalDate.class));
        }, start, end, locationCode);
        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWeekday(d) && !holidayDates.contains(d)) {
                count++;
            }
        }
        return count;
    }

    public boolean isBusinessDay(LocalDate date, String locationCode) {
        return businessDays(date, date, locationCode) == 1;
    }

    public LocalDate addBusinessDays(LocalDate start, int days, String locationCode) {
        LocalDate d = start;
        int remaining = days;
        while (remaining > 0) {
            d = d.plusDays(1);
            if (isBusinessDay(d, locationCode)) {
                remaining--;
            }
        }
        return d;
    }

    private static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
