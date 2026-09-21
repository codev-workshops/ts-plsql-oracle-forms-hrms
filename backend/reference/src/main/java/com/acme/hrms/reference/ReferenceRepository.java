package com.acme.hrms.reference;

import com.acme.hrms.reference.ReferenceDtos.DepartmentRef;
import com.acme.hrms.reference.ReferenceDtos.JobTitleRef;
import com.acme.hrms.reference.ReferenceDtos.LeaveTypeRef;
import com.acme.hrms.reference.ReferenceDtos.LocationRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Owning module for reads of departments / job_titles / job_grades / locations / leave_types
 * (ARCH-02: reference-service). Replaces the RG_* record groups of HRMS_EMPLOYEE / HRMS_LEAVE.
 */
@Repository
public class ReferenceRepository {

  private final JdbcTemplate jdbc;

  public ReferenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<DepartmentRef> departments(boolean activeOnly) {
    return jdbc.query(
        "select dept_id, dept_code, dept_name, parent_dept_id, location_code, active_flag"
            + " from departments"
            + (activeOnly ? " where active_flag = 'Y'" : "")
            + " order by dept_name, dept_id",
        (rs, i) ->
            new DepartmentRef(
                rs.getLong("dept_id"),
                rs.getString("dept_code"),
                rs.getString("dept_name"),
                nullableLong(rs, "parent_dept_id"),
                rs.getString("location_code"),
                flag(rs.getString("active_flag"))));
  }

  public List<JobTitleRef> jobTitles(boolean activeOnly) {
    return jdbc.query(
        "select j.job_id, j.job_code, j.job_title, j.job_family, g.grade_id, g.grade_code,"
            + " g.grade_name, g.min_salary, g.max_salary, j.active_flag"
            + " from job_titles j join job_grades g on g.grade_id = j.grade_id"
            + (activeOnly ? " where j.active_flag = 'Y'" : "")
            + " order by j.job_title, j.job_id",
        (rs, i) ->
            new JobTitleRef(
                rs.getLong("job_id"),
                rs.getString("job_code"),
                rs.getString("job_title"),
                rs.getString("job_family"),
                rs.getInt("grade_id"),
                rs.getString("grade_code"),
                rs.getString("grade_name"),
                money(rs.getBigDecimal("min_salary")),
                money(rs.getBigDecimal("max_salary")),
                flag(rs.getString("active_flag"))));
  }

  public List<LocationRef> locations(boolean activeOnly) {
    return jdbc.query(
        "select location_code, location_name, city, state_province, country_code, timezone,"
            + " active_flag from locations"
            + (activeOnly ? " where active_flag = 'Y'" : "")
            + " order by location_name, location_code",
        (rs, i) ->
            new LocationRef(
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getString("city"),
                rs.getString("state_province"),
                rs.getString("country_code"),
                rs.getString("timezone"),
                flag(rs.getString("active_flag"))));
  }

  public List<LeaveTypeRef> leaveTypes(boolean activeOnly) {
    return jdbc.query(
        "select leave_type_id, leave_type_code, leave_type_name, paid_flag, accrual_flag,"
            + " accrual_rate, max_balance, carryover_max, min_tenure_days, requires_approval,"
            + " requires_document, active_flag from leave_types"
            + (activeOnly ? " where active_flag = 'Y'" : "")
            + " order by leave_type_name, leave_type_id",
        (rs, i) ->
            new LeaveTypeRef(
                rs.getInt("leave_type_id"),
                rs.getString("leave_type_code"),
                rs.getString("leave_type_name"),
                flag(rs.getString("paid_flag")),
                flag(rs.getString("accrual_flag")),
                money(rs.getBigDecimal("accrual_rate")),
                money(rs.getBigDecimal("max_balance")),
                money(rs.getBigDecimal("carryover_max")),
                rs.getInt("min_tenure_days"),
                flag(rs.getString("requires_approval")),
                flag(rs.getString("requires_document")),
                flag(rs.getString("active_flag"))));
  }

  static boolean flag(@Nullable String yn) {
    return "Y".equals(yn);
  }

  /** Money is a decimal string with exactly 2 dp on the wire (never a float). */
  @Nullable
  static String money(@Nullable BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  @Nullable
  static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }
}
