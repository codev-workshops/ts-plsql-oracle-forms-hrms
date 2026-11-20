package com.hrms.leave;

import com.hrms.leave.LeaveModels.CarryoverRun;
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
 * PKG_LEAVE.process_carryover / expire_carryover (PKG-L05). Carryover keeps the legacy rules: the year's
 * remaining balance ({@code opening + accrued - used + adjustment}, pending excluded, as legacy) capped at
 * {@code CARRYOVER_MAX} becomes next year's {@code opening_balance} and {@code carryover_from_prev}, with
 * {@code carryover_expiry_dt} = 1 January + {@code CARRYOVER_EXPIRY} months.
 *
 * <p>DEF-03 fixes (OQ-04): each run is recorded in {@code leave_carryover_runs}, so a year is carried over
 * once and an expiry date is processed once (legacy re-runs reset {@code carryover_from_prev} and expired it
 * again); expiry removes only the unused part of the carried days (days used in the new year are taken from
 * the carryover first), where legacy subtracted the full carryover even after it had been used.
 */
@Service
public class LeaveCarryoverService {

    static final String CARRYOVER = "CARRYOVER";
    static final String EXPIRY = "EXPIRY";

    private final JdbcClient jdbc;
    private final LeaveAccrualService accrual;
    private final Clock clock;

    public LeaveCarryoverService(JdbcClient jdbc, LeaveAccrualService accrual, Clock clock) {
        this.jdbc = jdbc;
        this.accrual = accrual;
        this.clock = clock;
    }

    /** The run, and whether this call created it (false = already processed; nothing changed). */
    public record Outcome(CarryoverRun run, boolean created) {
    }

    private record Remaining(long empId, long typeId, BigDecimal days, Integer expiryMonths) {
    }

    private record Expiring(long balanceId, long empId, long typeId, int year, BigDecimal days) {
    }

    @Transactional
    public Outcome carryOver(int year, String actor) {
        OffsetDateTime now = now();
        LocalDate key = LocalDate.of(year + 1, 1, 1);
        Optional<Long> runId = startRun(CARRYOVER, key, year, actor, now);
        if (runId.isEmpty()) {
            return new Outcome(byKey(CARRYOVER, key), false);
        }
        List<Remaining> rows = jdbc.sql("""
                SELECT lb.emp_id, lb.leave_type_id,
                       CASE WHEN lt.carryover_max IS NULL
                            THEN lb.opening_balance + lb.accrued - lb.used + lb.adjustment
                            ELSE LEAST(lb.opening_balance + lb.accrued - lb.used + lb.adjustment, lt.carryover_max)
                       END,
                       lt.carryover_expiry
                FROM hrms.leave_balances lb JOIN hrms.leave_types lt ON lt.leave_type_id = lb.leave_type_id
                WHERE lb.calendar_year = :year
                  AND lb.opening_balance + lb.accrued - lb.used + lb.adjustment > 0
                ORDER BY lb.emp_id, lb.leave_type_id
                """).param("year", year)
                .query((rs, i) -> new Remaining(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3),
                        rs.getObject(4, Integer.class)))
                .list();
        int processed = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Remaining r : rows) {
            if (r.days().signum() <= 0) {
                continue;
            }
            accrual.initializeBalances(r.empId(), year + 1, actor, now);
            int updated = jdbc.sql("""
                    UPDATE hrms.leave_balances SET carryover_from_prev = :days, opening_balance = :days,
                        carryover_expiry_dt = :expiry, modified_by = :actor, modified_date = :now
                    WHERE emp_id = :empId AND leave_type_id = :typeId AND calendar_year = :next
                    """).param("days", r.days())
                    .param("expiry", r.expiryMonths() == null ? null : key.plusMonths(r.expiryMonths()))
                    .param("actor", actor).param("now", now).param("empId", r.empId()).param("typeId", r.typeId())
                    .param("next", year + 1).update();
            if (updated == 0) {
                continue; // inactive leave type: initialize_balances creates no row (legacy parity)
            }
            log(runId.get(), r.empId(), r.typeId(), year + 1, r.days(), actor, now);
            processed++;
            total = total.add(r.days());
        }
        finishRun(runId.get(), processed, total);
        return new Outcome(byKey(CARRYOVER, key), true);
    }

    @Transactional
    public Outcome expire(LocalDate asOf, String actor) {
        OffsetDateTime now = now();
        Optional<Long> runId = startRun(EXPIRY, asOf, null, actor, now);
        if (runId.isEmpty()) {
            return new Outcome(byKey(EXPIRY, asOf), false);
        }
        List<Expiring> rows = jdbc.sql("""
                SELECT balance_id, emp_id, leave_type_id, calendar_year,
                       GREATEST(carryover_from_prev - used, 0)
                FROM hrms.leave_balances
                WHERE carryover_expiry_dt <= :asOf AND carryover_from_prev > 0
                ORDER BY emp_id, leave_type_id, calendar_year
                FOR UPDATE
                """).param("asOf", asOf)
                .query((rs, i) -> new Expiring(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                        rs.getBigDecimal(5)))
                .list();
        int processed = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Expiring e : rows) {
            jdbc.sql("""
                    UPDATE hrms.leave_balances SET adjustment = adjustment - :days, carryover_from_prev = 0,
                        modified_by = :actor, modified_date = :now
                    WHERE balance_id = :id
                    """).param("days", e.days()).param("actor", actor).param("now", now).param("id", e.balanceId())
                    .update();
            log(runId.get(), e.empId(), e.typeId(), e.year(), e.days(), actor, now);
            processed++;
            total = total.add(e.days());
        }
        finishRun(runId.get(), processed, total);
        return new Outcome(byKey(EXPIRY, asOf), true);
    }

    private Optional<Long> startRun(String type, LocalDate key, Integer fromYear, String actor, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO hrms.leave_carryover_runs (run_type, run_key, from_year, balances_processed, total_days,
                    created_by, created_date)
                VALUES (:type, :key, :year, 0, 0, :actor, :now)
                ON CONFLICT (run_type, run_key) DO NOTHING
                RETURNING run_id
                """).param("type", type).param("key", key).param("year", fromYear).param("actor", actor)
                .param("now", now).query(Long.class).optional();
    }

    private void log(long runId, long empId, long typeId, int year, BigDecimal days, String actor,
            OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO hrms.leave_carryover_log (run_id, emp_id, leave_type_id, calendar_year, days,
                    created_by, created_date)
                VALUES (:runId, :empId, :typeId, :year, :days, :actor, :now)
                """).param("runId", runId).param("empId", empId).param("typeId", typeId).param("year", year)
                .param("days", days).param("actor", actor).param("now", now).update();
    }

    private void finishRun(long runId, int processed, BigDecimal total) {
        jdbc.sql("UPDATE hrms.leave_carryover_runs SET balances_processed = :n, total_days = :t WHERE run_id = :id")
                .param("n", processed).param("t", total).param("id", runId).update();
    }

    private CarryoverRun byKey(String type, LocalDate key) {
        return jdbc.sql("""
                SELECT run_id, run_type, from_year, run_key, balances_processed, total_days, created_by, created_date
                FROM hrms.leave_carryover_runs WHERE run_type = :type AND run_key = :key
                """).param("type", type).param("key", key)
                .query((rs, i) -> new CarryoverRun(rs.getLong(1), rs.getString(2), rs.getObject(3, Integer.class),
                        CARRYOVER.equals(rs.getString(2)) ? null : rs.getObject(4, LocalDate.class), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
                .single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
