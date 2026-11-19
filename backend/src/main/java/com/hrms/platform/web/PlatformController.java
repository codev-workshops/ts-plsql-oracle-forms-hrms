package com.hrms.platform.web;

import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.calendar.BusinessDayCalendar;
import com.hrms.platform.calendar.Holiday;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shared platform reads: health, holidays, lookups. */
@RestController
@RequestMapping("/api/v1")
public class PlatformController {

    private final JdbcTemplate jdbc;
    private final BusinessDayCalendar calendar;

    public PlatformController(JdbcTemplate jdbc, BusinessDayCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    public record LookupValue(String code, String value, int displayOrder) { }

    @GetMapping("/health")
    public Map<String, String> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }

    @GetMapping("/holidays")
    public List<Holiday> holidays(CurrentUser user, @RequestParam int year,
                                  @RequestParam(required = false) String locationCode) {
        return calendar.holidays(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), locationCode);
    }

    @GetMapping("/lookups/{type}")
    public List<LookupValue> lookups(CurrentUser user, @PathVariable String type) {
        return jdbc.query("""
                SELECT lookup_code, lookup_value, display_order FROM lookup_values
                WHERE lookup_type = ? AND active_flag = 'Y' ORDER BY display_order, lookup_code
                """, (rs, i) -> new LookupValue(rs.getString(1), rs.getString(2), rs.getInt(3)), type.toUpperCase());
    }
}
