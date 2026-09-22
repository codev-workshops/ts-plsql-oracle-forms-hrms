package com.acme.hrms.employee;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Sole writer of {@code employee_dependents}; removal is {@code active_flag='N'} only. */
@Repository
public class DependentRepository {

  /** Stored row - keeps the encrypted SSN inside the module. */
  public record DependentRow(
      long dependentId,
      long empId,
      String firstName,
      String lastName,
      String relationship,
      @Nullable LocalDate dateOfBirth,
      @Nullable String ssnEncrypted,
      boolean benefitsEnrolled,
      boolean active) {}

  public record DependentWrite(
      String firstName,
      String lastName,
      String relationship,
      @Nullable LocalDate dateOfBirth,
      @Nullable String ssnEncrypted,
      boolean benefitsEnrolled,
      boolean active,
      String actor) {}

  private static final RowMapper<DependentRow> ROW =
      (rs, i) ->
          new DependentRow(
              rs.getLong("dependent_id"),
              rs.getLong("emp_id"),
              rs.getString("first_name"),
              rs.getString("last_name"),
              rs.getString("relationship"),
              rs.getObject("date_of_birth", LocalDate.class),
              rs.getString("ssn_encrypted"),
              "Y".equals(rs.getString("benefits_enrolled")),
              "Y".equals(rs.getString("active_flag")));

  private final JdbcTemplate jdbc;

  public DependentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<DependentRow> findActive(long empId) {
    return jdbc.query(
        "select * from employee_dependents where emp_id = ? and active_flag = 'Y'"
            + " order by last_name, first_name, dependent_id",
        ROW,
        empId);
  }

  public Optional<DependentRow> findActive(long empId, long dependentId) {
    return jdbc
        .query(
            "select * from employee_dependents where emp_id = ? and dependent_id = ?"
                + " and active_flag = 'Y'",
            ROW,
            empId,
            dependentId)
        .stream()
        .findFirst();
  }

  public DependentRow insert(long empId, DependentWrite w) {
    Long id = jdbc.queryForObject("select nextval('seq_dependent')", Long.class);
    jdbc.update(
        "insert into employee_dependents (dependent_id, emp_id, first_name, last_name,"
            + " relationship, date_of_birth, ssn_encrypted, benefits_enrolled, active_flag,"
            + " created_by, created_date) values (?,?,?,?,?,?,?,?,'Y',?, current_timestamp)",
        id,
        empId,
        w.firstName(),
        w.lastName(),
        w.relationship(),
        w.dateOfBirth(),
        w.ssnEncrypted(),
        w.benefitsEnrolled() ? "Y" : "N",
        w.actor());
    return findById(id);
  }

  /** {@code ssnEncrypted == null} leaves the stored SSN unchanged. */
  public DependentRow update(long dependentId, DependentWrite w) {
    jdbc.update(
        "update employee_dependents set first_name = ?, last_name = ?, relationship = ?,"
            + " date_of_birth = ?, benefits_enrolled = ?, active_flag = ?,"
            + " ssn_encrypted = coalesce(?, ssn_encrypted), modified_by = ?,"
            + " modified_date = current_timestamp where dependent_id = ?",
        w.firstName(),
        w.lastName(),
        w.relationship(),
        w.dateOfBirth(),
        w.benefitsEnrolled() ? "Y" : "N",
        w.active() ? "Y" : "N",
        w.ssnEncrypted(),
        w.actor(),
        dependentId);
    return findById(dependentId);
  }

  private DependentRow findById(long dependentId) {
    return jdbc.queryForObject(
        "select * from employee_dependents where dependent_id = ?", ROW, dependentId);
  }
}
