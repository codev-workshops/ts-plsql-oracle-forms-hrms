package com.hrms.leave;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PKG_LEAVE.calculate_business_days (PKG-L03): Monday–Friday minus active HOLIDAYS whose location is NULL
 * (company-wide) or the employee's location. Exact-date match; observed holidays are data (DEF-02).
 */
@Component
public class BusinessDays {

    private final JdbcClient jdbc;

    public BusinessDays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int count(LocalDate start, LocalDate end, String locationCode) {
        return jdbc.sql("""
                SELECT count(*) FROM generate_series(CAST(:start AS date), CAST(:end AS date), interval '1 day') AS g(d)
                WHERE EXTRACT(ISODOW FROM g.d) < 6
                  AND NOT EXISTS (SELECT 1 FROM hrms.holidays h
                                  WHERE h.holiday_date = CAST(g.d AS date) AND h.active_flag
                                    AND (h.location_code IS NULL OR h.location_code = :loc))
                """).param("start", start).param("end", end).param("loc", locationCode)
                .query(Integer.class).single();
    }
}
