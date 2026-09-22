package com.acme.hrms.employee;

import com.acme.hrms.employee.EmployeeDtos.EmployeeSummary;
import com.acme.hrms.employee.EmployeeDtos.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Sole writer of {@code employees} (ARCH-01). Plain JDBC; the recursive reporting-chain check is a
 * PostgreSQL {@code WITH RECURSIVE} (replaces the {@code validate_manager} PL/SQL loop and {@code
 * CONNECT BY}).
 */
@Repository
public class EmployeeRepository {

  /** Depth cap mirroring {@code PKG_EMPLOYEE.c_max_hierarchy_depth}. */
  public static final int MAX_HIERARCHY_DEPTH = 100;

  public static final int MAX_TOKENS = 3;

  private static final String SELECT =
      "select e.emp_id, e.emp_number, e.first_name, e.middle_name, e.last_name, e.date_of_birth,"
          + " e.gender, e.marital_status, e.nationality, e.ssn_encrypted, e.email, e.phone_work,"
          + " e.phone_mobile, e.address_line1, e.address_line2, e.city, e.state_province,"
          + " e.postal_code, e.country_code, e.hire_date, e.termination_date, e.termination_reason,"
          + " e.dept_id, d.dept_name, e.job_id, j.job_title, g.grade_code, e.manager_emp_id,"
          + " case when m.emp_id is null then null else m.first_name || ' ' || m.last_name end"
          + " as manager_name, e.location_code, l.location_name, e.employment_type,"
          + " e.employment_status, e.active_flag, e.notes, e.version, e.created_by,"
          + " e.created_date, e.modified_by, e.modified_date"
          + " from employees e"
          + " join departments d on d.dept_id = e.dept_id"
          + " join job_titles j on j.job_id = e.job_id"
          + " left join job_grades g on g.grade_id = j.grade_id"
          + " left join employees m on m.emp_id = e.manager_emp_id"
          + " left join locations l on l.location_code = e.location_code";

  private static final RowMapper<EmployeeRow> ROW =
      (rs, i) ->
          new EmployeeRow(
              rs.getLong("emp_id"),
              rs.getString("emp_number"),
              rs.getString("first_name"),
              rs.getString("middle_name"),
              rs.getString("last_name"),
              date(rs, "date_of_birth"),
              rs.getString("gender"),
              rs.getString("marital_status"),
              rs.getString("nationality"),
              rs.getString("ssn_encrypted"),
              rs.getString("email"),
              rs.getString("phone_work"),
              rs.getString("phone_mobile"),
              rs.getString("address_line1"),
              rs.getString("address_line2"),
              rs.getString("city"),
              rs.getString("state_province"),
              rs.getString("postal_code"),
              rs.getString("country_code"),
              rs.getObject("hire_date", LocalDate.class),
              date(rs, "termination_date"),
              rs.getString("termination_reason"),
              rs.getLong("dept_id"),
              rs.getString("dept_name"),
              rs.getLong("job_id"),
              rs.getString("job_title"),
              rs.getString("grade_code"),
              nullableLong(rs, "manager_emp_id"),
              rs.getString("manager_name"),
              rs.getString("location_code"),
              rs.getString("location_name"),
              rs.getString("employment_type"),
              rs.getString("employment_status"),
              "Y".equals(rs.getString("active_flag")),
              rs.getString("notes"),
              rs.getInt("version"),
              rs.getString("created_by"),
              timestamp(rs, "created_date"),
              rs.getString("modified_by"),
              timestamp(rs, "modified_date"));

  /** Insert payload; every string is already trimmed / blank-to-null by the validation DTO. */
  public record NewEmployee(
      String empNumber,
      String firstName,
      @Nullable String middleName,
      String lastName,
      @Nullable LocalDate dateOfBirth,
      @Nullable String gender,
      @Nullable String maritalStatus,
      @Nullable String nationality,
      @Nullable String ssnEncrypted,
      @Nullable String email,
      @Nullable String phoneWork,
      @Nullable String phoneMobile,
      @Nullable String addressLine1,
      @Nullable String addressLine2,
      @Nullable String city,
      @Nullable String stateProvince,
      @Nullable String postalCode,
      @Nullable String countryCode,
      LocalDate hireDate,
      long deptId,
      long jobId,
      @Nullable Long managerEmpId,
      @Nullable String locationCode,
      String employmentType,
      @Nullable String notes,
      String createdBy) {}

  /** Editable set of PUT /api/employees/{id}; {@code ssnEncrypted == null} keeps the stored SSN. */
  public record EmployeeUpdate(
      String firstName,
      @Nullable String middleName,
      String lastName,
      @Nullable LocalDate dateOfBirth,
      @Nullable String gender,
      @Nullable String maritalStatus,
      @Nullable String nationality,
      @Nullable String ssnEncrypted,
      @Nullable String email,
      @Nullable String phoneWork,
      @Nullable String phoneMobile,
      @Nullable String addressLine1,
      @Nullable String addressLine2,
      @Nullable String city,
      @Nullable String stateProvince,
      @Nullable String postalCode,
      @Nullable String countryCode,
      long jobId,
      @Nullable Long managerEmpId,
      String employmentType,
      @Nullable String notes,
      String modifiedBy) {}

  public record SearchFilter(
      @Nullable String lastName,
      @Nullable String firstName,
      @Nullable String q,
      @Nullable Long deptId,
      @Nullable Long jobId,
      @Nullable Long managerEmpId,
      @Nullable String status,
      @Nullable Boolean active,
      @Nullable String locationCode,
      @Nullable LocalDate hireDateFrom,
      @Nullable LocalDate hireDateTo,
      @Nullable Long excludeEmpId) {}

  private final JdbcTemplate jdbc;

  public EmployeeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<EmployeeRow> findById(long empId) {
    return jdbc.query(SELECT + " where e.emp_id = ?", ROW, empId).stream().findFirst();
  }

  /** Row-locks the employee for the lifecycle operations (legacy {@code SELECT … FOR UPDATE}). */
  public Optional<EmployeeRow> lock(long empId) {
    jdbc.query("select emp_id from employees where emp_id = ? for update", rs -> {}, empId);
    return findById(empId);
  }

  /**
   * {@code 'EMP-' || LPAD(SEQ_EMP_NUMBER.nextval, 6, '0')}; draws again when the number is already
   * taken (seed data below the sequence start), so {@code -20002} is unreachable in practice.
   */
  public String nextEmpNumber() {
    for (int i = 0; i < 1000; i++) {
      Long next = jdbc.queryForObject("select nextval('seq_emp_number')", Long.class);
      String number = String.format(Locale.ROOT, "EMP-%06d", next);
      Integer taken =
          jdbc.queryForObject(
              "select count(*) from employees where emp_number = ?", Integer.class, number);
      if (taken == null || taken == 0) {
        return number;
      }
    }
    throw new IllegalStateException("seq_emp_number exhausted against existing employee numbers");
  }

  public long insert(NewEmployee e) {
    Long id = jdbc.queryForObject("select nextval('seq_employee')", Long.class);
    jdbc.update(
        "insert into employees (emp_id, emp_number, first_name, middle_name, last_name,"
            + " date_of_birth, gender, marital_status, nationality, ssn_encrypted, email,"
            + " phone_work, phone_mobile, address_line1, address_line2, city, state_province,"
            + " postal_code, country_code, hire_date, dept_id, job_id, manager_emp_id,"
            + " location_code, employment_type, employment_status, active_flag, notes, version,"
            + " created_by, created_date) values"
            + " (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','Y',?,0,?,"
            + " current_timestamp)",
        id,
        e.empNumber(),
        e.firstName(),
        text(e.middleName()),
        e.lastName(),
        e.dateOfBirth(),
        text(e.gender()),
        text(e.maritalStatus()),
        text(e.nationality()),
        e.ssnEncrypted(),
        text(e.email()),
        text(e.phoneWork()),
        text(e.phoneMobile()),
        text(e.addressLine1()),
        text(e.addressLine2()),
        text(e.city()),
        text(e.stateProvince()),
        text(e.postalCode()),
        text(e.countryCode()),
        e.hireDate(),
        e.deptId(),
        e.jobId(),
        e.managerEmpId(),
        text(e.locationCode()),
        e.employmentType(),
        text(e.notes()),
        e.createdBy());
    return id;
  }

  /** Returns false when {@code expectedVersion} is stale (optimistic lock, {@code CONFLICT}). */
  public boolean update(long empId, int expectedVersion, EmployeeUpdate u) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "update employees set first_name = ?, middle_name = ?, last_name = ?,"
                + " date_of_birth = ?, gender = ?, marital_status = ?, nationality = ?, email = ?,"
                + " phone_work = ?, phone_mobile = ?, address_line1 = ?, address_line2 = ?,"
                + " city = ?, state_province = ?, postal_code = ?, country_code = ?, job_id = ?,"
                + " manager_emp_id = ?, employment_type = ?, notes = ?, version = version + 1,"
                + " modified_by = ?, modified_date = current_timestamp");
    args.add(u.firstName());
    args.add(text(u.middleName()));
    args.add(u.lastName());
    args.add(u.dateOfBirth());
    args.add(text(u.gender()));
    args.add(text(u.maritalStatus()));
    args.add(text(u.nationality()));
    args.add(text(u.email()));
    args.add(text(u.phoneWork()));
    args.add(text(u.phoneMobile()));
    args.add(text(u.addressLine1()));
    args.add(text(u.addressLine2()));
    args.add(text(u.city()));
    args.add(text(u.stateProvince()));
    args.add(text(u.postalCode()));
    args.add(text(u.countryCode()));
    args.add(u.jobId());
    args.add(u.managerEmpId());
    args.add(u.employmentType());
    args.add(text(u.notes()));
    args.add(u.modifiedBy());
    if (u.ssnEncrypted() != null) {
      sql.append(", ssn_encrypted = ?");
      args.add(u.ssnEncrypted());
    }
    sql.append(" where emp_id = ? and version = ?");
    args.add(empId);
    args.add(expectedVersion);
    return jdbc.update(sql.toString(), args.toArray()) == 1;
  }

  public void terminate(long empId, LocalDate terminationDate, String reason, String actor) {
    jdbc.update(
        "update employees set employment_status = 'TERMINATED', active_flag = 'N',"
            + " termination_date = ?, termination_reason = ?, version = version + 1,"
            + " modified_by = ?, modified_date = current_timestamp where emp_id = ?",
        terminationDate,
        reason,
        actor,
        empId);
  }

  public void transfer(
      long empId,
      long deptId,
      long jobId,
      @Nullable Long managerEmpId,
      @Nullable String locationCode,
      String actor) {
    jdbc.update(
        "update employees set dept_id = ?, job_id = ?, manager_emp_id = ?, location_code = ?,"
            + " version = version + 1, modified_by = ?, modified_date = current_timestamp"
            + " where emp_id = ?",
        deptId,
        jobId,
        managerEmpId,
        locationCode,
        actor,
        empId);
  }

  /** Oracle {@code VARCHAR2 '' IS NULL}: blanks never reach PostgreSQL as empty strings. */
  @Nullable
  static String text(@Nullable String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  public boolean departmentActive(long deptId) {
    return exists("select 1 from departments where dept_id = ? and active_flag = 'Y'", deptId);
  }

  public boolean jobActive(long jobId) {
    return exists("select 1 from job_titles where job_id = ? and active_flag = 'Y'", jobId);
  }

  public boolean locationActive(String locationCode) {
    return exists(
        "select 1 from locations where location_code = ? and active_flag = 'Y'", locationCode);
  }

  /** {@code validate_manager}: the manager must be {@code ACTIVE} and {@code active_flag='Y'}. */
  public boolean managerActive(long managerEmpId) {
    return exists(
        "select 1 from employees where emp_id = ? and employment_status = 'ACTIVE'"
            + " and active_flag = 'Y'",
        managerEmpId);
  }

  /**
   * True when {@code empId} appears in the reporting chain above {@code managerEmpId} (or is the
   * manager itself), i.e. making {@code managerEmpId} the manager of {@code empId} would close a
   * cycle. Walks {@code manager_emp_id} upwards with {@code WITH RECURSIVE}, bounded by {@link
   * #MAX_HIERARCHY_DEPTH} and by {@code CYCLE}-safe path tracking so pre-existing loops cannot
   * spin.
   */
  public boolean isInReportingChain(long empId, long managerEmpId) {
    Integer hits =
        jdbc.queryForObject(
            "with recursive chain (emp_id, manager_emp_id, depth, path) as ("
                + "  select e.emp_id, e.manager_emp_id, 1, array[e.emp_id]"
                + "    from employees e where e.emp_id = ?"
                + "  union all"
                + "  select p.emp_id, p.manager_emp_id, c.depth + 1, c.path || p.emp_id"
                + "    from employees p join chain c on p.emp_id = c.manager_emp_id"
                + "   where c.depth < ? and not (p.emp_id = any (c.path))"
                + ") select count(*) from chain where emp_id = ?",
            Integer.class,
            managerEmpId,
            MAX_HIERARCHY_DEPTH,
            empId);
    return hits != null && hits > 0;
  }

  /** Direct and indirect reports of {@code managerEmpId} ({@code CONNECT BY PRIOR} replacement). */
  public List<Long> reportingChainBelow(long managerEmpId) {
    return jdbc.queryForList(
        "with recursive tree (emp_id, depth, path) as ("
            + "  select e.emp_id, 1, array[e.emp_id] from employees e where e.manager_emp_id = ?"
            + "  union all"
            + "  select e.emp_id, t.depth + 1, t.path || e.emp_id"
            + "    from employees e join tree t on e.manager_emp_id = t.emp_id"
            + "   where t.depth < ? and not (e.emp_id = any (t.path))"
            + ") select distinct emp_id from tree order by emp_id",
        Long.class,
        managerEmpId,
        MAX_HIERARCHY_DEPTH);
  }

  /** {@code -20502}: another active employee (excluding {@code excludeEmpId}) holds the e-mail. */
  public boolean emailInUse(String email, @Nullable Long excludeEmpId) {
    if (text(email) == null) {
      return false;
    }
    return exists(
        "select 1 from employees where upper(email) = upper(?) and active_flag = 'Y'"
            + " and emp_id <> coalesce(?, -1)",
        email,
        excludeEmpId);
  }

  public Page<EmployeeRow> search(SearchFilter f, int page, int size) {
    Where w = where(f);
    Long total =
        jdbc.queryForObject("select count(*) from employees e" + w.sql, Long.class, w.args());
    List<Object> args = new ArrayList<>(w.argList());
    args.add(size);
    args.add((long) page * size);
    List<EmployeeRow> rows =
        jdbc.query(
            SELECT + w.sql + " order by e.last_name, e.first_name, e.emp_id limit ? offset ?",
            ROW,
            args.toArray());
    return Page.of(rows, page, size, total == null ? 0 : total);
  }

  /** {@code fields=id,name,jobTitle} projection (manager LOV, {@code RG_MANAGERS}). */
  public Page<EmployeeSummary> searchSummaries(SearchFilter f, int page, int size) {
    Where w = where(f);
    Long total =
        jdbc.queryForObject("select count(*) from employees e" + w.sql, Long.class, w.args());
    List<Object> args = new ArrayList<>(w.argList());
    args.add(size);
    args.add((long) page * size);
    List<EmployeeSummary> rows =
        jdbc.query(
            "select e.emp_id, e.emp_number, e.first_name, e.last_name, j.job_title"
                + " from employees e left join job_titles j on j.job_id = e.job_id"
                + w.sql
                + " order by e.last_name, e.first_name, e.emp_id limit ? offset ?",
            (rs, i) ->
                new EmployeeSummary(
                    rs.getLong("emp_id"),
                    rs.getString("emp_number"),
                    rs.getString("first_name") + " " + rs.getString("last_name"),
                    rs.getString("job_title")),
            args.toArray());
    return Page.of(rows, page, size, total == null ? 0 : total);
  }

  private record Where(String sql, List<Object> argList) {
    Object[] args() {
      return argList.toArray();
    }
  }

  private static Where where(SearchFilter f) {
    StringBuilder sql = new StringBuilder(" where 1 = 1");
    List<Object> args = new ArrayList<>();
    if (f.lastName() != null) {
      sql.append(" and upper(e.last_name) like upper(?) escape '\\'");
      args.add(escapeLike(f.lastName()) + "%");
    }
    if (f.firstName() != null) {
      sql.append(" and upper(e.first_name) like upper(?) escape '\\'");
      args.add(escapeLike(f.firstName()) + "%");
    }
    for (String token : tokens(f.q())) {
      sql.append(
          " and (lower(e.first_name) like ? or lower(e.last_name) like ?"
              + " or lower(e.first_name || ' ' || e.last_name) like ? or lower(e.emp_number) like ?)");
      String contains = "%" + escapeLike(token.toLowerCase(Locale.ROOT)) + "%";
      for (int i = 0; i < 4; i++) {
        args.add(contains);
      }
    }
    if (f.deptId() != null) {
      sql.append(" and e.dept_id = ?");
      args.add(f.deptId());
    }
    if (f.jobId() != null) {
      sql.append(" and e.job_id = ?");
      args.add(f.jobId());
    }
    if (f.managerEmpId() != null) {
      sql.append(" and e.manager_emp_id = ?");
      args.add(f.managerEmpId());
    }
    if (f.status() != null) {
      sql.append(" and e.employment_status = ?");
      args.add(f.status());
    }
    if (Boolean.TRUE.equals(f.active())) {
      sql.append(" and e.active_flag = 'Y'");
    }
    if (f.locationCode() != null) {
      sql.append(" and e.location_code = ?");
      args.add(f.locationCode());
    }
    if (f.hireDateFrom() != null) {
      sql.append(" and e.hire_date >= ?");
      args.add(f.hireDateFrom());
    }
    if (f.hireDateTo() != null) {
      sql.append(" and e.hire_date <= ?");
      args.add(f.hireDateTo());
    }
    if (f.excludeEmpId() != null) {
      sql.append(" and e.emp_id <> ?");
      args.add(f.excludeEmpId());
    }
    return new Where(sql.toString(), args);
  }

  static List<String> tokens(@Nullable String q) {
    if (q == null || q.isBlank()) {
      return List.of();
    }
    String[] parts = q.trim().split("\\s+");
    return List.of(parts).subList(0, Math.min(parts.length, MAX_TOKENS));
  }

  static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private boolean exists(String sql, Object... args) {
    return !jdbc.queryForList(sql, args).isEmpty();
  }

  @Nullable
  static LocalDate date(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, LocalDate.class);
  }

  @Nullable
  static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toLocalDateTime();
  }

  @Nullable
  static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }
}
