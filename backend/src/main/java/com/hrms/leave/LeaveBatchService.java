package com.hrms.leave;

import com.hrms.leave.LeaveRepository.TypeRow;
import com.hrms.platform.audit.AuditService;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.employee.EmployeeSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave batch operations (PKG_LEAVE.initialize_balances / run_monthly_accrual /
 * process_carryover / expire_carryover). All are idempotent: re-running a month's accrual,
 * a year's carryover or an expiry has no further effect.
 */
@Service
public class LeaveBatchService {

    private final JdbcTemplate jdbc;
    private final LeaveRepository repo;
    private final EmployeeLookup employees;
    private final AuditService audit;

    public LeaveBatchService(JdbcTemplate jdbc, LeaveRepository repo, EmployeeLookup employees, AuditService audit) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.employees = employees;
        this.audit = audit;
    }

    @Transactional
    public int initializeBalances(long empId, int year, String user) {
        int created = 0;
        for (TypeRow t : repo.allTypes()) {
            if (t.active()) {
                created += repo.ensureBalance(empId, t.type().leaveTypeId(), year, user);
            }
        }
        return created;
    }

    /** Adds one month's accrual per active employee and MONTHLY accrual type, capped at MAX_BALANCE. */
    @Transactional
    public int runMonthlyAccrual(LocalDate accrualDate, String user) {
        int year = accrualDate.getYear();
        LocalDate monthStart = accrualDate.withDayOfMonth(1);
        List<TypeRow> types = repo.allTypes().stream()
                .filter(t -> t.active() && t.type().accrual() && "MONTHLY".equals(t.accrualFrequency()))
                .toList();
        int rows = 0;
        for (EmployeeSummary emp : employees.activeEmployees()) {
            for (TypeRow t : types) {
                int typeId = t.type().leaveTypeId();
                if (emp.hireDate() != null && emp.hireDate().isAfter(accrualDate)) {
                    continue;
                }
                if (emp.hireDate() != null
                        && ChronoUnit.DAYS.between(emp.hireDate(), accrualDate) < t.type().minTenureDays()) {
                    continue;
                }
                Integer already = jdbc.queryForObject("""
                        SELECT count(*) FROM leave_accrual_log
                        WHERE emp_id = ? AND leave_type_id = ? AND accrual_date >= ? AND accrual_date < ?
                        """, Integer.class, emp.empId(), typeId, monthStart, monthStart.plusMonths(1));
                if (already != null && already > 0) {
                    continue;
                }
                repo.ensureBalance(emp.empId(), typeId, year, user);
                BigDecimal current = repo.balance(emp.empId(), typeId, year).orElseThrow().available();
                BigDecimal amount = t.type().accrualRate();
                BigDecimal max = t.type().maxBalance();
                if (max != null && current.add(amount).compareTo(max) > 0) {
                    amount = max.subtract(current).max(BigDecimal.ZERO);
                }
                jdbc.update("""
                        UPDATE leave_balances SET accrued = accrued + ?, modified_by = ?, modified_date = now()
                        WHERE emp_id = ? AND leave_type_id = ? AND calendar_year = ?
                        """, amount, user, emp.empId(), typeId, year);
                jdbc.update("""
                        INSERT INTO leave_accrual_log (emp_id, leave_type_id, accrual_date, accrual_amount,
                                                       balance_after, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, emp.empId(), typeId, accrualDate, amount, current.add(amount), user);
                rows++;
            }
        }
        audit.record("LEAVE_ACCRUAL_LOG", 0, AuditService.Action.INSERT, null,
                Map.of("operation", "MONTHLY_ACCRUAL", "accrualDate", accrualDate.toString(), "rows", rows), user);
        return rows;
    }

    /** Moves unused balance (capped at CARRYOVER_MAX) of {@code year} into year + 1. */
    @Transactional
    public int processCarryover(int year, String user) {
        int rows = 0;
        for (TypeRow t : repo.allTypes()) {
            BigDecimal carryMax = t.type().carryoverMax();
            if (!t.active() || carryMax == null || carryMax.signum() <= 0) {
                continue;
            }
            Integer expiryMonths = t.carryoverExpiryMonths();
            LocalDate expiry = expiryMonths == null ? null : LocalDate.of(year + 1, 1, 1).plusMonths(expiryMonths);
            rows += jdbc.update("""
                    INSERT INTO leave_balances (emp_id, leave_type_id, calendar_year, opening_balance,
                                                carryover_from_prev, carryover_expiry_dt, created_by)
                    SELECT b.emp_id, b.leave_type_id, ?, carry, carry, ?, ?
                    FROM (SELECT emp_id, leave_type_id,
                                 least(greatest(opening_balance + accrued - used + adjustment, 0), ?) AS carry
                          FROM leave_balances WHERE leave_type_id = ? AND calendar_year = ?) b
                    JOIN employees e ON e.emp_id = b.emp_id AND e.employment_status = 'ACTIVE'
                    WHERE b.carry > 0
                    ON CONFLICT (emp_id, leave_type_id, calendar_year) DO UPDATE
                      SET opening_balance = EXCLUDED.opening_balance,
                          carryover_from_prev = EXCLUDED.carryover_from_prev,
                          carryover_expiry_dt = EXCLUDED.carryover_expiry_dt,
                          modified_by = EXCLUDED.created_by, modified_date = now()
                    """, year + 1, expiry, user, carryMax, t.type().leaveTypeId(), year);
        }
        audit.record("LEAVE_BALANCES", 0, AuditService.Action.UPDATE, null,
                Map.of("operation", "CARRYOVER", "year", year, "rows", rows), user);
        return rows;
    }

    /** Forfeits carried-over days whose expiry date has passed; clears the expiry so it runs once. */
    @Transactional
    public int expireCarryover(LocalDate asOf, String user) {
        int rows = jdbc.update("""
                UPDATE leave_balances SET adjustment = adjustment - carryover_from_prev, carryover_expiry_dt = NULL,
                       modified_by = ?, modified_date = now()
                WHERE carryover_expiry_dt IS NOT NULL AND carryover_expiry_dt < ? AND carryover_from_prev > 0
                """, user, asOf);
        audit.record("LEAVE_BALANCES", 0, AuditService.Action.UPDATE, null,
                Map.of("operation", "EXPIRE_CARRYOVER", "asOf", asOf.toString(), "rows", rows), user);
        return rows;
    }
}
