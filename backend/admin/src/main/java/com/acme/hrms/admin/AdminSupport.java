package com.acme.hrms.admin;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CurrentCaller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.zip.CRC32;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Shared plumbing for the reference-data owners: actor, audit JSON, flag mapping, lookups. */
@Component
public class AdminSupport {
  private final NamedParameterJdbcTemplate jdbc;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AdminSupport(
      NamedParameterJdbcTemplate jdbc, AuditService audit, ObjectMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.mapper = mapper;
    this.clock = clock;
  }

  public NamedParameterJdbcTemplate jdbc() {
    return jdbc;
  }

  public String actor() {
    return CurrentCaller.require().userId();
  }

  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  public static String flag(@Nullable Boolean b, boolean dflt) {
    return (b == null ? dflt : b) ? "Y" : "N";
  }

  public static boolean flag(@Nullable String s) {
    return "Y".equals(s);
  }

  public static String activeWhere(@Nullable Boolean active, String col) {
    if (active == null) {
      return "";
    }
    return " and " + col + " = '" + (active ? "Y" : "N") + "'";
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public void audit(
      String table,
      long recordId,
      AuditService.Action action,
      @Nullable Object oldValues,
      @Nullable Object newValues) {
    audit.log(
        table,
        recordId,
        action,
        oldValues == null ? null : json(oldValues),
        newValues == null ? null : json(newValues),
        actor(),
        null,
        null);
  }

  /** Natural-key tables (LOCATIONS) have no numeric id; a stable CRC32 stands in. */
  public static long recordIdOf(String code) {
    CRC32 crc = new CRC32();
    crc.update(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return crc.getValue();
  }

  public void requireActiveEmployee(@Nullable Long empId) {
    if (empId == null) {
      return;
    }
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where emp_id = :id and employment_status = 'ACTIVE'",
            Map.of("id", empId),
            Integer.class);
    if (n == null || n == 0) {
      throw new HrmsException(
          ErrorCode.EMPLOYEE_NOT_FOUND,
          ErrorCode.EMPLOYEE_NOT_FOUND.defaultMessage() + ": " + empId,
          "managerEmpId");
    }
  }

  public void requireActiveDepartment(@Nullable Long deptId) {
    if (deptId == null) {
      return;
    }
    Integer n =
        jdbc.queryForObject(
            "select count(*) from departments where dept_id = :id and active_flag = 'Y'",
            Map.of("id", deptId),
            Integer.class);
    if (n == null || n == 0) {
      throw error(ErrorCode.INVALID_DEPARTMENT, "parentDeptId", deptId);
    }
  }

  public void requireActiveLocation(@Nullable String code) {
    if (code == null) {
      return;
    }
    Integer n =
        jdbc.queryForObject(
            "select count(*) from locations where location_code = :c and active_flag = 'Y'",
            Map.of("c", code),
            Integer.class);
    if (n == null || n == 0) {
      throw error(ErrorCode.INVALID_GRADE_OR_LOCATION, "locationCode", "location", code);
    }
  }

  public void requireActiveGrade(int gradeId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from job_grades where grade_id = :id and active_flag = 'Y'",
            Map.of("id", gradeId),
            Integer.class);
    if (n == null || n == 0) {
      throw error(ErrorCode.INVALID_GRADE_OR_LOCATION, "gradeId", "grade", gradeId);
    }
  }

  public static HrmsException error(ErrorCode code, @Nullable String field, Object... args) {
    return new HrmsException(code, String.format(code.defaultMessage(), args), field);
  }

  public int count(String sql, Map<String, ?> params) {
    Integer n = jdbc.queryForObject(sql, params, Integer.class);
    return n == null ? 0 : n;
  }
}
