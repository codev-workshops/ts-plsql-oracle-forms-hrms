package com.hrms.leave;

import com.hrms.leave.LeaveModels.AccrualRun;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_LEAVE.run_monthly_accrual (PKG-L04, OQ-12). Same rules as the legacy job (ACTIVE employees, active
 * MONTHLY accrual types, MIN_TENURE_DAYS on the accrual date, MAX_BALANCE cap against the available
 * balance), but idempotent per calendar month ({@code leave_accrual_runs}) and one transaction instead of
 * a COMMIT every 100 employees.
 */
@Service
public class LeaveAccrualService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public LeaveAccrualService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The outcome: the run, and whether this call created it (false = the month had already been accrued). */
    public record Outcome(AccrualRun run, boolean created) {
    }

    private record Emp(long empId, LocalDate hireDate) {
    }

    private record Type(long id, BigDecimal rate, BigDecimal maxBalance, Integer minTenureDays) {
    }

    @Transactional
    public Outcome run(LocalDate accrualDate, String actor) {
        LocalDate period = accrualDate.withDayOfMonth(1);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        Optional<Long> runId = jdbc.sql("""
                INSERT INTO hrms.leave_accrual_runs (accrual_period, accrual_date, employees_processed,
                    entries_created, total_accrued, created_by, created_date)
                VALUES (:period, :date, 0, 0, 0, :actor, :now)
                ON CONFLICT (accrual_period) DO NOTHING
                RETURNING run_id
                """).param("period", period).param("date", accrualDate).param("actor", actor).param("now", now)
                .query(Long.class).optional();
        if (runId.isEmpty()) {
            return new Outcome(byPeriod(period), false);
        }

        List<Emp> employees = jdbc.sql("""
                SELECT emp_id, hire_date FROM hrms.employees
                WHERE employment_status = 'ACTIVE' AND active_flag ORDER BY emp_id
                """).query((rs, i) -> new Emp(rs.getLong(1), rs.getObject(2, LocalDate.class))).list();
        List<Type> types = jdbc.sql("""
                SELECT leave_type_id, accrual_rate, max_balance, min_tenure_days FROM hrms.leave_types
                WHERE active_flag AND accrual_flag AND accrual_frequency = 'MONTHLY' AND accrual_rate IS NOT NULL
                ORDER BY leave_type_id
                """).query((rs, i) -> new Type(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getObject(4, Integer.class))).list();

        int year = accrualDate.getYear();
        int entries = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Emp e : employees) {
            for (Type t : types) {
                // Legacy: TRUNC(p_accrual_date) - HIRE_DATE >= MIN_TENURE_DAYS (a NULL tenure never matches).
                if (t.minTenureDays() == null || ChronoUnit.DAYS.between(e.hireDate(), accrualDate) < t.minTenureDays()) {
                    continue;
                }
                BigDecimal current = jdbc.sql("""
                        SELECT available FROM hrms.leave_balances
                        WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :year
                        """).param("empId", e.empId()).param("typeId", t.id()).param("year", year)
                        .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
                BigDecimal accrued = t.maxBalance() == null || current.add(t.rate()).compareTo(t.maxBalance()) <= 0
                        ? t.rate() : t.maxBalance().subtract(current).max(BigDecimal.ZERO);
                if (accrued.signum() <= 0) {
                    continue;
                }
                initializeBalances(e.empId(), year, actor, now);
                BigDecimal after = jdbc.sql("""
                        UPDATE hrms.leave_balances SET accrued = accrued + :amount, modified_by = :actor,
                            modified_date = :now
                        WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :year
                        RETURNING available
                        """).param("amount", accrued).param("actor", actor).param("now", now)
                        .param("empId", e.empId()).param("typeId", t.id()).param("year", year)
                        .query(BigDecimal.class).single();
                jdbc.sql("""
                        INSERT INTO hrms.leave_accrual_log (emp_id, leave_type_id, accrual_date, accrual_amount,
                            balance_after, run_id, created_by, created_date)
                        VALUES (:empId, :typeId, :date, :amount, :after, :runId, :actor, :now)
                        """).param("empId", e.empId()).param("typeId", t.id()).param("date", accrualDate)
                        .param("amount", accrued).param("after", after).param("runId", runId.get())
                        .param("actor", actor).param("now", now).update();
                entries++;
                total = total.add(accrued);
            }
        }
        jdbc.sql("""
                UPDATE hrms.leave_accrual_runs SET employees_processed = :emps, entries_created = :entries,
                    total_accrued = :total
                WHERE run_id = :runId
                """).param("emps", employees.size()).param("entries", entries).param("total", total)
                .param("runId", runId.get()).update();
        return new Outcome(byPeriod(period), true);
    }

    /** PKG_LEAVE.initialize_balances: a zero row for every active leave type that has none for the year. */
    void initializeBalances(long empId, int year, String actor, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, created_by, created_date)
                SELECT :empId, t.leave_type_id, :year, :actor, :now FROM hrms.leave_types t WHERE t.active_flag
                ON CONFLICT (emp_id, leave_type_id, calendar_year) DO NOTHING
                """).param("empId", empId).param("year", year).param("actor", actor).param("now", now).update();
    }

    private AccrualRun byPeriod(LocalDate period) {
        return jdbc.sql("""
                SELECT run_id, accrual_period, accrual_date, employees_processed, entries_created, total_accrued,
                       created_by, created_date
                FROM hrms.leave_accrual_runs WHERE accrual_period = :period
                """).param("period", period)
                .query((rs, i) -> new AccrualRun(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6),
                        rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
                .single();
    }
}
