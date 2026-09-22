package com.acme.hrms.employee;

import com.acme.hrms.employee.EmployeeDtos.EmployeeHistoryEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Sole writer of {@code employee_history} ({@code PKG_EMPLOYEE.log_history}). Rows are written only
 * by the operations marked {@code x-history} in the contract, never by a client.
 */
@Repository
public class EmployeeHistoryRepository {

  public record HistoryRow(
      long empId,
      String changeType,
      LocalDate effectiveDate,
      @Nullable Long oldDeptId,
      @Nullable Long newDeptId,
      @Nullable Long oldJobId,
      @Nullable Long newJobId,
      @Nullable Long oldManagerId,
      @Nullable Long newManagerId,
      @Nullable BigDecimal oldSalary,
      @Nullable BigDecimal newSalary,
      @Nullable String oldLocation,
      @Nullable String newLocation,
      @Nullable String reasonCode,
      @Nullable String comments,
      String createdBy) {}

  private final JdbcTemplate jdbc;

  public EmployeeHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(HistoryRow h) {
    Long id = jdbc.queryForObject("select nextval('seq_emp_history')", Long.class);
    jdbc.update(
        "insert into employee_history (hist_id, emp_id, change_type, effective_date, old_dept_id,"
            + " new_dept_id, old_job_id, new_job_id, old_manager_id, new_manager_id, old_salary,"
            + " new_salary, old_location, new_location, reason_code, comments, created_by,"
            + " created_date) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, current_timestamp)",
        id,
        h.empId(),
        h.changeType(),
        h.effectiveDate(),
        h.oldDeptId(),
        h.newDeptId(),
        h.oldJobId(),
        h.newJobId(),
        h.oldManagerId(),
        h.newManagerId(),
        h.oldSalary(),
        h.newSalary(),
        h.oldLocation(),
        h.newLocation(),
        h.reasonCode(),
        h.comments(),
        h.createdBy());
    return id;
  }

  /** {@code ORDER BY effective_date DESC, hist_id DESC}, joined to display names. */
  public List<EmployeeHistoryEntry> findByEmployee(long empId) {
    return jdbc.query(
        "select h.*, od.dept_name as old_dept_name, nd.dept_name as new_dept_name,"
            + " oj.job_title as old_job_title, nj.job_title as new_job_title,"
            + " case when om.emp_id is null then null else om.first_name || ' ' || om.last_name end"
            + " as old_manager_name,"
            + " case when nm.emp_id is null then null else nm.first_name || ' ' || nm.last_name end"
            + " as new_manager_name"
            + " from employee_history h"
            + " left join departments od on od.dept_id = h.old_dept_id"
            + " left join departments nd on nd.dept_id = h.new_dept_id"
            + " left join job_titles oj on oj.job_id = h.old_job_id"
            + " left join job_titles nj on nj.job_id = h.new_job_id"
            + " left join employees om on om.emp_id = h.old_manager_id"
            + " left join employees nm on nm.emp_id = h.new_manager_id"
            + " where h.emp_id = ? order by h.effective_date desc, h.hist_id desc",
        (rs, i) ->
            new EmployeeHistoryEntry(
                rs.getLong("hist_id"),
                rs.getLong("emp_id"),
                rs.getString("change_type"),
                rs.getObject("effective_date", LocalDate.class),
                EmployeeRepository.nullableLong(rs, "old_dept_id"),
                rs.getString("old_dept_name"),
                EmployeeRepository.nullableLong(rs, "new_dept_id"),
                rs.getString("new_dept_name"),
                EmployeeRepository.nullableLong(rs, "old_job_id"),
                rs.getString("old_job_title"),
                EmployeeRepository.nullableLong(rs, "new_job_id"),
                rs.getString("new_job_title"),
                EmployeeRepository.nullableLong(rs, "old_manager_id"),
                rs.getString("old_manager_name"),
                EmployeeRepository.nullableLong(rs, "new_manager_id"),
                rs.getString("new_manager_name"),
                money(rs.getBigDecimal("old_salary")),
                money(rs.getBigDecimal("new_salary")),
                rs.getString("old_location"),
                rs.getString("new_location"),
                rs.getString("reason_code"),
                rs.getString("comments"),
                rs.getString("created_by"),
                EmployeeRepository.timestamp(rs, "created_date")),
        empId);
  }

  @Nullable
  private static String money(@Nullable BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
