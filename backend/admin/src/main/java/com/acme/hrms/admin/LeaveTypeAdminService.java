package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.LeaveType;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.LeaveTypeRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner of LEAVE_TYPES (CHK_ACCRUAL_FREQ + carryover policy enforced here). */
@Service
public class LeaveTypeAdminService {
  private static final String SELECT =
      """
      select t.*, (select count(*) from leave_requests r
                    where r.leave_type_id = t.leave_type_id
                      and r.status = 'PENDING') as pending_requests
        from leave_types t
      """;

  private static final RowMapper<LeaveType> MAPPER =
      (rs, i) ->
          new LeaveType(
              rs.getInt("leave_type_id"),
              rs.getString("leave_type_code"),
              rs.getString("leave_type_name"),
              AdminSupport.flag(rs.getString("paid_flag")),
              AdminSupport.flag(rs.getString("accrual_flag")),
              AdminDtos.money(rs.getBigDecimal("accrual_rate")),
              rs.getString("accrual_frequency"),
              AdminDtos.money(rs.getBigDecimal("max_balance")),
              AdminDtos.money(rs.getBigDecimal("carryover_max")),
              rs.getObject("carryover_expiry", Integer.class),
              rs.getInt("min_tenure_days"),
              AdminSupport.flag(rs.getString("requires_approval")),
              AdminSupport.flag(rs.getString("requires_document")),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("pending_requests"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final AdminSupport s;

  public LeaveTypeAdminService(AdminSupport s) {
    this.s = s;
  }

  public List<LeaveType> list(@Nullable Boolean active) {
    return s.jdbc()
        .query(
            SELECT
                + " where 1=1"
                + AdminSupport.activeWhere(active, "t.active_flag")
                + " order by t.leave_type_code",
            Map.of(),
            MAPPER);
  }

  public LeaveType get(int id) {
    List<LeaveType> rows =
        s.jdbc().query(SELECT + " where t.leave_type_id = :id", Map.of("id", id), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public LeaveType create(LeaveTypeRequest r) {
    if (s.count(
            "select count(*) from leave_types where leave_type_code = :c",
            Map.of("c", r.getLeaveTypeCode()))
        > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "leaveTypeCode", r.getLeaveTypeCode());
    }
    checkPolicy(r);
    int id =
        Objects.requireNonNull(
            s.jdbc().queryForObject("select nextval('seq_leave_type')", Map.of(), Integer.class));
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into leave_types (leave_type_id, leave_type_code, leave_type_name, paid_flag,
                                     accrual_flag, accrual_rate, accrual_frequency, max_balance,
                                     carryover_max, carryover_expiry, min_tenure_days,
                                     requires_approval, requires_document, active_flag,
                                     created_by, created_date)
            values (:id, :code, :name, :paid, :accrual, :rate, :freq, :maxBal, :coMax, :coExp,
                    :tenure, :reqAppr, :reqDoc, :active, :actor, :now)
            """,
            p);
    LeaveType created = get(id);
    s.audit("LEAVE_TYPES", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public LeaveType update(int id, LeaveTypeRequest r) {
    LeaveType old = get(id);
    if (!old.leaveTypeCode().equals(r.getLeaveTypeCode())) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "leaveTypeCode", r.getLeaveTypeCode());
    }
    checkPolicy(r);
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            update leave_types
               set leave_type_name = :name, paid_flag = :paid, accrual_flag = :accrual,
                   accrual_rate = :rate, accrual_frequency = :freq, max_balance = :maxBal,
                   carryover_max = :coMax, carryover_expiry = :coExp, min_tenure_days = :tenure,
                   requires_approval = :reqAppr, requires_document = :reqDoc,
                   active_flag = :active, modified_by = :actor, modified_date = :now
             where leave_type_id = :id
            """,
            p);
    LeaveType updated = get(id);
    s.audit("LEAVE_TYPES", id, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(int id) {
    LeaveType old = get(id);
    if (!old.activeFlag()) {
      return;
    }
    if (old.pendingRequests() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Leave type",
          old.leaveTypeCode(),
          old.pendingRequests(),
          "pending requests");
    }
    s.jdbc()
        .update(
            "update leave_types set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where leave_type_id = :id",
            Map.of("id", id, "actor", s.actor(), "now", s.now()));
    s.audit("LEAVE_TYPES", id, Action.STATUS_CHANGE, old, get(id));
  }

  private static void checkPolicy(LeaveTypeRequest r) {
    boolean accrual = r.getAccrualFlag() == null || r.getAccrualFlag();
    if (accrual && r.getAccrualRate() == null) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "Accrual rate is required when accrual is enabled",
          "accrualRate");
    }
    if (accrual && r.getAccrualFrequency() == null) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "Accrual frequency is required when accrual is enabled",
          "accrualFrequency");
    }
    if (r.getCarryoverMax() != null
        && r.getMaxBalance() != null
        && r.getCarryoverMax().compareTo(r.getMaxBalance()) > 0) {
      throw new HrmsException(
          ErrorCode.REFERENCE_VALUE_RULE,
          "Carryover maximum cannot exceed the maximum balance",
          "carryoverMax");
    }
  }

  private static Map<String, Object> params(LeaveTypeRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getLeaveTypeCode());
    p.put("name", r.getLeaveTypeName());
    p.put("paid", AdminSupport.flag(r.getPaidFlag(), true));
    p.put("accrual", AdminSupport.flag(r.getAccrualFlag(), true));
    p.put("rate", r.getAccrualRate());
    p.put("freq", r.getAccrualFrequency());
    p.put("maxBal", r.getMaxBalance());
    p.put("coMax", r.getCarryoverMax());
    p.put("coExp", r.getCarryoverExpiry());
    p.put("tenure", r.getMinTenureDays() == null ? 0 : r.getMinTenureDays());
    p.put("reqAppr", AdminSupport.flag(r.getRequiresApproval(), true));
    p.put("reqDoc", AdminSupport.flag(r.getRequiresDocument(), false));
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
