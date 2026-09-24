package com.acme.hrms.leave;

import com.acme.hrms.leave.LeaveDtos.LeaveBalance;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Owner of {@code leave_balances}. {@code available} is the STORED generated column (VAL-05: {@code
 * opening_balance + accrued - used + adjustment - pending}); it is read, never written. All
 * mutations are keyed on {@code uk_leave_bal (emp_id, leave_type_id, calendar_year)} and are no-ops
 * when the row does not exist (QUIRK-02).
 */
@Repository
public class LeaveBalanceRepository {

  private static final String SELECT =
      "select b.balance_id, b.leave_type_id, lt.leave_type_code, lt.leave_type_name,"
          + " b.calendar_year, b.opening_balance, b.accrued, b.used, b.adjustment, b.pending,"
          + " b.carryover_from_prev, b.available"
          + " from leave_balances b join leave_types lt on lt.leave_type_id = b.leave_type_id";

  private static final RowMapper<LeaveBalance> MAPPER =
      (rs, i) ->
          new LeaveBalance(
              rs.getLong("balance_id"),
              rs.getInt("leave_type_id"),
              rs.getString("leave_type_code"),
              rs.getString("leave_type_name"),
              rs.getInt("calendar_year"),
              rs.getBigDecimal("opening_balance"),
              rs.getBigDecimal("accrued"),
              rs.getBigDecimal("used"),
              rs.getBigDecimal("adjustment"),
              rs.getBigDecimal("pending"),
              rs.getBigDecimal("carryover_from_prev"),
              rs.getBigDecimal("available"));

  private final JdbcTemplate jdbc;

  public LeaveBalanceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<LeaveBalance> listForEmployee(long empId, int year) {
    return jdbc.query(
        SELECT + " where b.emp_id = ? and b.calendar_year = ? order by lt.leave_type_name",
        MAPPER,
        empId,
        year);
  }

  public Optional<LeaveBalance> find(long empId, int leaveTypeId, int year) {
    return jdbc
        .query(
            SELECT + " where b.emp_id = ? and b.leave_type_id = ? and b.calendar_year = ?",
            MAPPER,
            empId,
            leaveTypeId,
            year)
        .stream()
        .findFirst();
  }

  /** {@code PKG_LEAVE.get_leave_balance}: the stored {@code available}, {@code 0} without a row. */
  public BigDecimal available(long empId, int leaveTypeId, int year) {
    return find(empId, leaveTypeId, year).map(LeaveBalance::available).orElse(BigDecimal.ZERO);
  }

  public int addPending(long empId, int leaveTypeId, int year, BigDecimal days, String by) {
    return adjust(empId, leaveTypeId, year, days, BigDecimal.ZERO, by);
  }

  public int addUsed(long empId, int leaveTypeId, int year, BigDecimal days, String by) {
    return adjust(empId, leaveTypeId, year, BigDecimal.ZERO, days, by);
  }

  /** approve: {@code pending -= days, used += days}. */
  public int pendingToUsed(long empId, int leaveTypeId, int year, BigDecimal days, String by) {
    return adjust(empId, leaveTypeId, year, days.negate(), days, by);
  }

  private int adjust(
      long empId,
      int leaveTypeId,
      int year,
      BigDecimal pendingDelta,
      BigDecimal usedDelta,
      String by) {
    return jdbc.update(
        "update leave_balances set pending = pending + ?, used = used + ?, modified_by = ?,"
            + " modified_date = current_timestamp"
            + " where emp_id = ? and leave_type_id = ? and calendar_year = ?",
        pendingDelta,
        usedDelta,
        by,
        empId,
        leaveTypeId,
        year);
  }
}
