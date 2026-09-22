package com.acme.hrms.salary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Read-only employee and reference-data projection needed by salary-module. */
@Repository
public class SalaryEmployeeLookup {

  private final JdbcTemplate jdbc;

  public SalaryEmployeeLookup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<EmployeeRef> find(long empId) {
    return jdbc
        .query(
            "select e.emp_id, e.employment_status, e.hire_date, e.job_id,"
                + " g.min_salary, g.max_salary, e.manager_emp_id"
                + " from employees e left join job_titles j on j.job_id = e.job_id"
                + " left join job_grades g on g.grade_id = j.grade_id"
                + " where e.emp_id = ?",
            (rs, i) ->
                new EmployeeRef(
                    rs.getLong("emp_id"),
                    rs.getString("employment_status"),
                    rs.getObject("hire_date", LocalDate.class),
                    nullableLong(rs.getObject("job_id")),
                    rs.getBigDecimal("min_salary"),
                    rs.getBigDecimal("max_salary"),
                    nullableLong(rs.getObject("manager_emp_id"))),
            empId)
        .stream()
        .findFirst();
  }

  private static Long nullableLong(@Nullable Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  public record EmployeeRef(
      long empId,
      String employmentStatus,
      LocalDate hireDate,
      @Nullable Long jobId,
      @Nullable BigDecimal gradeMin,
      @Nullable BigDecimal gradeMax,
      @Nullable Long managerEmpId) {}
}
