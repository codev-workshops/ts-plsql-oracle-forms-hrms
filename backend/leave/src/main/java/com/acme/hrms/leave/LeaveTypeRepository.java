package com.acme.hrms.leave;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Read-only view of {@code leave_types} (owned by P0 {@code reference}; the wire endpoint stays
 * {@code GET /api/reference/leave-types}). Only the columns PKG_LEAVE reads.
 */
@Repository
public class LeaveTypeRepository {

  public record LeaveType(
      int leaveTypeId,
      String leaveTypeCode,
      String leaveTypeName,
      boolean accrual,
      @Nullable BigDecimal accrualRate,
      @Nullable String accrualFrequency,
      @Nullable BigDecimal maxBalance,
      @Nullable BigDecimal carryoverMax,
      @Nullable Integer carryoverExpiryMonths,
      int minTenureDays,
      boolean requiresApproval) {}

  private final JdbcTemplate jdbc;

  public LeaveTypeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<LeaveType> findActive(int leaveTypeId) {
    return jdbc
        .query(
            "select leave_type_id, leave_type_code, leave_type_name, accrual_flag, accrual_rate,"
                + " accrual_frequency, max_balance, carryover_max, carryover_expiry,"
                + " min_tenure_days, requires_approval"
                + " from leave_types where leave_type_id = ? and active_flag = 'Y'",
            (rs, i) ->
                new LeaveType(
                    rs.getInt("leave_type_id"),
                    rs.getString("leave_type_code"),
                    rs.getString("leave_type_name"),
                    "Y".equals(rs.getString("accrual_flag")),
                    rs.getBigDecimal("accrual_rate"),
                    rs.getString("accrual_frequency"),
                    rs.getBigDecimal("max_balance"),
                    rs.getBigDecimal("carryover_max"),
                    rs.getObject("carryover_expiry", Integer.class),
                    rs.getInt("min_tenure_days"),
                    "Y".equals(rs.getString("requires_approval"))),
            leaveTypeId)
        .stream()
        .findFirst();
  }
}
