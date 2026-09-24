package com.acme.hrms.auth.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Owning module for user_accounts / user_roles / role_permissions (ARCH-02: auth-service). */
@Repository
public class UserAccountRepository {

  /** user_accounts joined with the employee behind it. */
  public record Account(
      long userId,
      long empId,
      String username,
      String passwordHash,
      boolean mustChangePassword,
      String accountStatus,
      String empNumber,
      String email,
      String firstName,
      String lastName,
      @Nullable Long deptId,
      @Nullable String jobTitle,
      String employmentStatus,
      boolean empActive) {

    public boolean canLogin() {
      return "ACTIVE".equals(accountStatus) && isEmployeeActive();
    }

    public boolean isEmployeeActive() {
      return "ACTIVE".equals(employmentStatus) && empActive;
    }

    public String displayName() {
      return firstName + " " + lastName;
    }
  }

  private static final String SELECT =
      "select u.user_id, u.emp_id, u.username, u.password_hash, u.must_change_password, u.status,"
          + " e.emp_number, e.email, e.first_name, e.last_name, e.dept_id, j.job_title,"
          + " e.employment_status, e.active_flag"
          + " from user_accounts u join employees e on e.emp_id = u.emp_id"
          + " left join job_titles j on j.job_id = e.job_id";

  private final JdbcTemplate jdbc;

  public UserAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Case-insensitive e-mail match. Two employees sharing an e-mail (SEC-10 on Oracle) yield empty
   * so the caller answers -20301 like every other failure.
   */
  public Optional<Account> findByEmail(String email) {
    try {
      List<Account> rows =
          jdbc.query(SELECT + " where lower(e.email) = lower(?)", this::map, email.trim());
      return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    } catch (IncorrectResultSizeDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<Account> findById(long userId) {
    return jdbc.query(SELECT + " where u.user_id = ?", this::map, userId).stream().findFirst();
  }

  public Set<String> authorities(long userId) {
    return new LinkedHashSet<>(
        jdbc.queryForList(
            "select distinct rp.authority from user_roles ur"
                + " join role_permissions rp on rp.role_id = ur.role_id"
                + " where ur.user_id = ? order by rp.authority",
            String.class,
            userId));
  }

  public void updatePassword(long userId, String hash, Instant when, String modifiedBy) {
    jdbc.update(
        "update user_accounts set password_hash = ?, password_changed_at = ?,"
            + " must_change_password = false, modified_by = ?, modified_date = ?"
            + " where user_id = ?",
        hash,
        ts(when),
        modifiedBy,
        ts(when),
        userId);
  }

  /** Termination disables the login ({@code status = DISABLED}); no-op without an account. */
  public boolean disableByEmployee(long empId, Instant when, String modifiedBy) {
    return jdbc.update(
            "update user_accounts set status = 'DISABLED', modified_by = ?, modified_date = ?"
                + " where emp_id = ? and status = 'ACTIVE'",
            modifiedBy,
            ts(when),
            empId)
        > 0;
  }

  public void recordFailure(long userId) {
    jdbc.update(
        "update user_accounts set failed_attempts = failed_attempts + 1 where user_id = ?", userId);
  }

  public void resetFailures(long userId) {
    jdbc.update(
        "update user_accounts set failed_attempts = 0, locked_until = null where user_id = ?",
        userId);
  }

  static LocalDateTime ts(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private Account map(ResultSet rs, int i) throws SQLException {
    long dept = rs.getLong("dept_id");
    Long deptId = rs.wasNull() ? null : dept;
    return new Account(
        rs.getLong("user_id"),
        rs.getLong("emp_id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getBoolean("must_change_password"),
        rs.getString("status"),
        rs.getString("emp_number"),
        rs.getString("email"),
        rs.getString("first_name"),
        rs.getString("last_name"),
        deptId,
        rs.getString("job_title"),
        rs.getString("employment_status"),
        "Y".equals(rs.getString("active_flag")));
  }
}
