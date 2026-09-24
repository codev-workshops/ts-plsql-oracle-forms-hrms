package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.PayElement;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.PayElementRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner of PAY_ELEMENTS. Rows {@code 0} (ERROR sentinel), {@code 1} (BASE_PAY) and {@code 100–103}
 * (taxes) are the ids the payroll TaxEngine writes ({@code PayrollConstants}, {@code
 * PayElementStartupValidator}); they accept only name/GL/priority edits and are never deactivated.
 * Payroll reads the table per request, so no cache is invalidated here.
 */
@Service
public class PayElementService {
  static final Set<Long> RESERVED_IDS = Set.of(0L, 1L, 100L, 101L, 102L, 103L);

  private static final String SELECT =
      """
      select p.*,
             (select count(*) from employee_pay_elements e
               where e.element_id = p.element_id
                 and e.active_flag = 'Y'
                 and (e.end_date is null or e.end_date >= current_date)) as active_employee_elements
        from pay_elements p
      """;

  private static final RowMapper<PayElement> MAPPER =
      (rs, i) -> {
        long id = rs.getLong("element_id");
        return new PayElement(
            id,
            rs.getString("element_code"),
            rs.getString("element_name"),
            rs.getString("element_type"),
            rs.getString("calculation_type"),
            AdminDtos.money(rs.getBigDecimal("default_amount")),
            AdminDtos.money(rs.getBigDecimal("default_percentage")),
            AdminSupport.flag(rs.getString("taxable_flag")),
            AdminSupport.flag(rs.getString("pretax_flag")),
            AdminSupport.flag(rs.getString("employer_paid")),
            rs.getString("gl_account_code"),
            rs.getInt("priority_order"),
            AdminSupport.flag(rs.getString("active_flag")),
            RESERVED_IDS.contains(id),
            rs.getInt("active_employee_elements"),
            rs.getString("created_by"),
            rs.getObject("created_date", LocalDateTime.class),
            rs.getString("modified_by"),
            rs.getObject("modified_date", LocalDateTime.class));
      };

  private final AdminSupport s;

  public PayElementService(AdminSupport s) {
    this.s = s;
  }

  public List<PayElement> list(@Nullable Boolean active, @Nullable String elementType) {
    StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
    sql.append(AdminSupport.activeWhere(active, "p.active_flag"));
    Map<String, Object> p = new HashMap<>();
    if (elementType != null) {
      sql.append(" and p.element_type = :type");
      p.put("type", elementType);
    }
    sql.append(" order by p.priority_order, p.element_code");
    return s.jdbc().query(sql.toString(), p, MAPPER);
  }

  public PayElement get(long id) {
    List<PayElement> rows =
        s.jdbc().query(SELECT + " where p.element_id = :id", Map.of("id", id), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public PayElement create(PayElementRequest r) {
    if ("TAX".equals(r.getElementType())) {
      throw AdminSupport.error(
          ErrorCode.PAY_ELEMENT_PROTECTED,
          "elementType",
          0,
          "ERROR",
          "elementType TAX is reserved for the tax engine");
    }
    if (s.count(
            "select count(*) from pay_elements where element_code = :c",
            Map.of("c", r.getElementCode()))
        > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "elementCode", r.getElementCode());
    }
    Map<String, Object> p = params(r);
    p.put("actor", s.actor());
    p.put("now", s.now());
    long id =
        s.jdbc()
            .queryForObject(
                """
                insert into pay_elements (element_id, element_code, element_name, element_type,
                                          calculation_type, default_amount, default_percentage,
                                          taxable_flag, pretax_flag, employer_paid, gl_account_code,
                                          priority_order, active_flag, created_by, created_date)
                values (nextval('seq_pay_element'), :code, :name, :type, :calc, :amount, :pct,
                        :taxable, :pretax, :employer, :gl, :priority, :active, :actor, :now)
                returning element_id
                """,
                p,
                Long.class);
    PayElement created = get(id);
    s.audit("PAY_ELEMENTS", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public PayElement update(long id, PayElementRequest r) {
    PayElement old = get(id);
    if (!old.elementCode().equals(r.getElementCode())) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "elementCode", r.getElementCode());
    }
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    if (old.reserved()) {
      requireReservedFieldsUnchanged(old, r, p);
      s.jdbc()
          .update(
              """
              update pay_elements
                 set element_name = :name, gl_account_code = :gl, priority_order = :priority,
                     modified_by = :actor, modified_date = :now
               where element_id = :id
              """,
              p);
    } else {
      s.jdbc()
          .update(
              """
              update pay_elements
                 set element_name = :name, element_type = :type, calculation_type = :calc,
                     default_amount = :amount, default_percentage = :pct, taxable_flag = :taxable,
                     pretax_flag = :pretax, employer_paid = :employer, gl_account_code = :gl,
                     priority_order = :priority, active_flag = :active,
                     modified_by = :actor, modified_date = :now
               where element_id = :id
              """,
              p);
    }
    PayElement updated = get(id);
    s.audit("PAY_ELEMENTS", id, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(long id) {
    PayElement old = get(id);
    if (old.reserved()) {
      throw AdminSupport.error(
          ErrorCode.PAY_ELEMENT_PROTECTED, null, id, old.elementCode(), "activeFlag is immutable");
    }
    if (!old.activeFlag()) {
      return;
    }
    if (old.activeEmployeeElements() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Pay element",
          old.elementCode(),
          old.activeEmployeeElements(),
          "employee pay elements");
    }
    s.jdbc()
        .update(
            "update pay_elements set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where element_id = :id",
            Map.of("id", id, "actor", s.actor(), "now", s.now()));
    s.audit("PAY_ELEMENTS", id, Action.STATUS_CHANGE, old, get(id));
  }

  /** Reserved rows accept only name/GL/priority; the ERROR sentinel (row 0) accepts nothing. */
  private static void requireReservedFieldsUnchanged(
      PayElement old, PayElementRequest r, Map<String, Object> p) {
    String changed = null;
    if (!old.elementType().equals(r.getElementType())) {
      changed = "elementType";
    } else if (!old.calculationType().equals(r.getCalculationType())) {
      changed = "calculationType";
    } else if (!sameMoney(old.defaultAmount(), r.getDefaultAmount())) {
      changed = "defaultAmount";
    } else if (!sameMoney(old.defaultPercentage(), r.getDefaultPercentage())) {
      changed = "defaultPercentage";
    } else if (old.taxableFlag() != flag(r.getTaxableFlag(), true)) {
      changed = "taxableFlag";
    } else if (old.pretaxFlag() != flag(r.getPretaxFlag(), false)) {
      changed = "pretaxFlag";
    } else if (old.employerPaid() != flag(r.getEmployerPaid(), false)) {
      changed = "employerPaid";
    } else if (old.activeFlag() != flag(r.getActiveFlag(), true)) {
      changed = "activeFlag";
    } else if (old.elementId() == 0L) {
      if (!old.elementName().equals(r.getElementName())) {
        changed = "elementName";
      } else if (!Objects.equals(old.glAccountCode(), r.getGlAccountCode())) {
        changed = "glAccountCode";
      } else if (old.priorityOrder() != (int) p.get("priority")) {
        changed = "priorityOrder";
      }
    }
    if (changed != null) {
      throw AdminSupport.error(
          ErrorCode.PAY_ELEMENT_PROTECTED,
          changed,
          old.elementId(),
          old.elementCode(),
          changed + " is immutable");
    }
  }

  private static boolean flag(@Nullable Boolean b, boolean dflt) {
    return b == null ? dflt : b;
  }

  /** Legacy seed rows carry a null default where the frozen DTO requires {@code 0}. */
  private static boolean sameMoney(@Nullable String current, @Nullable BigDecimal r) {
    if (current == null) {
      return r == null || r.signum() == 0;
    }
    return r != null && current.equals(AdminDtos.money(r));
  }

  private static Map<String, Object> params(PayElementRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getElementCode());
    p.put("name", r.getElementName());
    p.put("type", r.getElementType());
    p.put("calc", r.getCalculationType());
    p.put("amount", r.getDefaultAmount());
    p.put("pct", r.getDefaultPercentage());
    p.put("taxable", AdminSupport.flag(r.getTaxableFlag(), true));
    p.put("pretax", AdminSupport.flag(r.getPretaxFlag(), false));
    p.put("employer", AdminSupport.flag(r.getEmployerPaid(), false));
    p.put("gl", r.getGlAccountCode());
    p.put("priority", r.getPriorityOrder() == null ? 100 : r.getPriorityOrder());
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
