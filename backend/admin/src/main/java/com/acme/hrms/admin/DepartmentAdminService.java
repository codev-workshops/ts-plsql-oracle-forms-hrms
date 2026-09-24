package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.Department;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.DepartmentRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner of DEPARTMENTS (HRMS_ADMIN departments block). */
@Service
public class DepartmentAdminService {
  private static final String SELECT =
      """
      select d.dept_id, d.dept_code, d.dept_name, d.parent_dept_id, p.dept_name as parent_dept_name,
             d.cost_center, d.manager_emp_id,
             case when m.emp_id is null then null
                  else m.first_name || ' ' || m.last_name end as manager_name,
             d.location_code, d.active_flag, d.created_by, d.created_date, d.modified_by,
             d.modified_date,
             (select count(*) from employees e
               where e.dept_id = d.dept_id and e.employment_status = 'ACTIVE') as active_employees
        from departments d
        left join departments p on p.dept_id = d.parent_dept_id
        left join employees m on m.emp_id = d.manager_emp_id
      """;

  private static final RowMapper<Department> MAPPER =
      (rs, i) ->
          new Department(
              rs.getLong("dept_id"),
              rs.getString("dept_code"),
              rs.getString("dept_name"),
              rs.getObject("parent_dept_id", Long.class),
              rs.getString("parent_dept_name"),
              rs.getString("cost_center"),
              rs.getObject("manager_emp_id", Long.class),
              rs.getString("manager_name"),
              rs.getString("location_code"),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("active_employees"),
              rs.getString("created_by"),
              rs.getObject("created_date", java.time.LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", java.time.LocalDateTime.class));

  private final AdminSupport s;

  public DepartmentAdminService(AdminSupport s) {
    this.s = s;
  }

  public List<Department> list(@Nullable Boolean active) {
    return s.jdbc()
        .query(
            SELECT
                + " where 1=1"
                + AdminSupport.activeWhere(active, "d.active_flag")
                + " order by d.dept_code",
            Map.of(),
            MAPPER);
  }

  public Department get(long deptId) {
    List<Department> rows =
        s.jdbc().query(SELECT + " where d.dept_id = :id", Map.of("id", deptId), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public Department create(DepartmentRequest r) {
    if (s.count(
            "select count(*) from departments where dept_code = :c", Map.of("c", r.getDeptCode()))
        > 0) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "deptCode", r.getDeptCode());
    }
    validateRefs(r, null);
    long id =
        Objects.requireNonNull(
            s.jdbc().queryForObject("select nextval('seq_department')", Map.of(), Long.class));
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into departments (dept_id, dept_code, dept_name, parent_dept_id, cost_center,
                                     manager_emp_id, location_code, active_flag, created_by,
                                     created_date)
            values (:id, :code, :name, :parent, :cc, :mgr, :loc, :active, :actor, :now)
            """,
            p);
    Department created = get(id);
    s.audit("DEPARTMENTS", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public Department update(long deptId, DepartmentRequest r) {
    Department old = get(deptId);
    if (!old.deptCode().equals(r.getDeptCode())) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "deptCode", r.getDeptCode());
    }
    validateRefs(r, deptId);
    Map<String, Object> p = params(r);
    p.put("id", deptId);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            update departments
               set dept_name = :name, parent_dept_id = :parent, cost_center = :cc,
                   manager_emp_id = :mgr, location_code = :loc, active_flag = :active,
                   modified_by = :actor, modified_date = :now
             where dept_id = :id
            """,
            p);
    Department updated = get(deptId);
    s.audit("DEPARTMENTS", deptId, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(long deptId) {
    Department old = get(deptId);
    if (!old.activeFlag()) {
      return;
    }
    if (old.activeEmployees() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Department",
          old.deptCode(),
          old.activeEmployees(),
          "employees");
    }
    int children =
        s.count(
            "select count(*) from departments where parent_dept_id = :id and active_flag = 'Y'",
            Map.of("id", deptId));
    if (children > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Department",
          old.deptCode(),
          children,
          "child departments");
    }
    s.jdbc()
        .update(
            "update departments set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where dept_id = :id",
            Map.of("id", deptId, "actor", s.actor(), "now", s.now()));
    s.audit("DEPARTMENTS", deptId, Action.STATUS_CHANGE, old, get(deptId));
  }

  private void validateRefs(DepartmentRequest r, @Nullable Long selfId) {
    Long parent = r.getParentDeptId() == null ? null : r.getParentDeptId().longValue();
    Long mgr = r.getManagerEmpId() == null ? null : r.getManagerEmpId().longValue();
    s.requireActiveDepartment(parent);
    s.requireActiveEmployee(mgr);
    s.requireActiveLocation(r.getLocationCode());
    if (parent != null && selfId != null) {
      checkCycle(selfId, parent);
    }
  }

  /** Walks the parent chain from the proposed parent; hitting self (or a loop) is -20605. */
  private void checkCycle(long selfId, long parent) {
    Integer hit =
        s.jdbc()
            .queryForObject(
                """
                with recursive chain as (
                  select d.dept_id, d.parent_dept_id, array[d.dept_id] as visited, 1 as depth
                    from departments d where d.dept_id = :parent
                  union all
                  select d.dept_id, d.parent_dept_id, c.visited || d.dept_id, c.depth + 1
                    from departments d join chain c on d.dept_id = c.parent_dept_id
                   where not d.dept_id = any (c.visited) and c.depth < 100
                )
                select count(*) from chain where dept_id = :self
                """,
                Map.of("parent", parent, "self", selfId),
                Integer.class);
    if (parent == selfId || (hit != null && hit > 0)) {
      throw AdminSupport.error(ErrorCode.DEPARTMENT_CYCLE, "parentDeptId", parent);
    }
  }

  private static Map<String, Object> params(DepartmentRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getDeptCode());
    p.put("name", r.getDeptName());
    p.put("parent", r.getParentDeptId());
    p.put("cc", r.getCostCenter());
    p.put("mgr", r.getManagerEmpId());
    p.put("loc", r.getLocationCode());
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
