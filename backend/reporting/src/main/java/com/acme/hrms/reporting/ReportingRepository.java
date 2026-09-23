package com.acme.hrms.reporting;

import com.acme.hrms.reporting.ReportingDtos.CompensationByDepartment;
import com.acme.hrms.reporting.ReportingDtos.EmployeeCompensationRow;
import com.acme.hrms.reporting.ReportingDtos.EmployeeDirectoryRow;
import com.acme.hrms.reporting.ReportingDtos.HeadcountByDepartment;
import com.acme.hrms.reporting.ReportingDtos.LeaveSummaryRow;
import com.acme.hrms.reporting.ReportingDtos.LeaveUtilizationByType;
import com.acme.hrms.reporting.ReportingDtos.OrgHierarchyRow;
import com.acme.hrms.reporting.ReportingDtos.PayrollLatestRow;
import com.acme.hrms.reporting.ReportingDtos.PayrollSummary;
import com.acme.hrms.reporting.ReportingDtos.PendingApprovalRow;
import com.acme.hrms.reporting.ReportingDtos.PendingSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL transcriptions of the {@code VW_*} views / {@code PKG_REPORTING} ref cursors on the
 * PostgreSQL schema (tests/reconciliation/pg is the reference). No view is recreated, no PL/pgSQL,
 * no bridge to Oracle. Every query takes the paging window as {@code :limit/:offset} and returns
 * the total through {@code count(*) over ()}.
 */
@Repository
public class ReportingRepository {

  /** A page of rows plus the unpaged total. */
  public record Rows<T>(List<T> rows, long total) {}

  private final NamedParameterJdbcTemplate jdbc;

  public ReportingRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Oracle {@code MONTHS_BETWEEN(a, b)} for two {@code date} expressions: whole months when the
   * days of month match or both are month ends, otherwise plus {@code (day(a) - day(b)) / 31}.
   */
  static String monthsBetween(String a, String b) {
    String lastDay = "extract(day from date_trunc('month', %s) + interval '1 month - 1 day')";
    return "((extract(year from "
        + a
        + ") - extract(year from "
        + b
        + ")) * 12"
        + " + (extract(month from "
        + a
        + ") - extract(month from "
        + b
        + "))"
        + " + case when extract(day from "
        + a
        + ") = extract(day from "
        + b
        + ") then 0"
        + " when extract(day from "
        + a
        + ") = "
        + String.format(lastDay, a)
        + " and extract(day from "
        + b
        + ") = "
        + String.format(lastDay, b)
        + " then 0"
        + " else (extract(day from "
        + a
        + ") - extract(day from "
        + b
        + ")) / 31.0 end)";
  }

  /** {@code TRUNC(MONTHS_BETWEEN(a, b) / 12, 1)} as numeric. */
  static String tenureYears(String a, String b) {
    return "trunc((" + monthsBetween(a, b) + " / 12)::numeric, 1)";
  }

  private static final String WINDOW = " limit :limit offset :offset";

  // ------------------------------------------------------------------ employee directory
  private static final String DIRECTORY_FROM =
      " from employees e"
          + " join departments d on e.dept_id = d.dept_id"
          + " join job_titles j on e.job_id = j.job_id"
          + " join job_grades g on j.grade_id = g.grade_id"
          + " left join locations l on e.location_code = l.location_code"
          + " left join employees m on e.manager_emp_id = m.emp_id"
          + " where e.employment_status = 'ACTIVE' and e.active_flag = 'Y'"
          + " and (:deptId::bigint is null or e.dept_id = :deptId)"
          + " and (:locationCode::varchar is null or e.location_code = :locationCode)";

  public Rows<EmployeeDirectoryRow> employeeDirectory(
      LocalDate asOf, @Nullable Long deptId, @Nullable String locationCode, int limit, int offset) {
    String sql =
        "select count(*) over () as total, e.emp_id, e.emp_number, e.first_name, e.last_name,"
            + " e.first_name || ' ' || e.last_name as full_name, e.email, e.phone_work, e.hire_date,"
            + " "
            + tenureYears(":asOf::date", "e.hire_date")
            + " as tenure_years,"
            + " e.dept_id, d.dept_code, d.dept_name, d.cost_center,"
            + " e.job_id, j.job_code, j.job_title, j.job_family,"
            + " g.grade_id, g.grade_code, g.grade_name,"
            + " e.location_code, l.location_name, l.city, l.state_province,"
            + " e.manager_emp_id, m.first_name || ' ' || m.last_name as manager_name"
            + DIRECTORY_FROM
            + " order by e.emp_id"
            + WINDOW;
    return page(
        sql,
        params(asOf, limit, offset)
            .addValue("deptId", deptId)
            .addValue("locationCode", locationCode),
        (rs, i) ->
            new EmployeeDirectoryRow(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone_work"),
                rs.getObject("hire_date", LocalDate.class),
                ReportingDtos.years(rs.getBigDecimal("tenure_years")),
                rs.getLong("dept_id"),
                rs.getString("dept_code"),
                rs.getString("dept_name"),
                rs.getString("cost_center"),
                rs.getLong("job_id"),
                rs.getString("job_code"),
                rs.getString("job_title"),
                rs.getString("job_family"),
                rs.getInt("grade_id"),
                rs.getString("grade_code"),
                rs.getString("grade_name"),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getString("city"),
                rs.getString("state_province"),
                nullableLong(rs, "manager_emp_id"),
                rs.getString("manager_name")));
  }

  /** {@code PKG_REPORTING.headcount_report}: active, hired on/before asOf, per department. */
  public List<HeadcountByDepartment> headcountByDepartment(
      LocalDate asOf, @Nullable Long deptId, @Nullable String locationCode) {
    String sql =
        "select d.dept_id, d.dept_code, d.dept_name, d.location_code, count(*) as headcount,"
            + " trunc(avg("
            + monthsBetween(":asOf::date", "e.hire_date")
            + ") / 12, 1) as avg_tenure_years"
            + " from employees e join departments d on e.dept_id = d.dept_id"
            + " where e.employment_status = 'ACTIVE' and e.active_flag = 'Y'"
            + " and e.hire_date <= :asOf::date"
            + " and (:deptId::bigint is null or e.dept_id = :deptId)"
            + " and (:locationCode::varchar is null or e.location_code = :locationCode)"
            + " group by d.dept_id, d.dept_code, d.dept_name, d.location_code"
            + " order by d.dept_name, d.dept_id";
    return jdbc.query(
        sql,
        new MapSqlParameterSource("asOf", asOf)
            .addValue("deptId", deptId)
            .addValue("locationCode", locationCode),
        (rs, i) ->
            new HeadcountByDepartment(
                rs.getLong("dept_id"),
                rs.getString("dept_code"),
                rs.getString("dept_name"),
                rs.getString("location_code"),
                rs.getInt("headcount"),
                ReportingDtos.years(rs.getBigDecimal("avg_tenure_years"))));
  }

  // ------------------------------------------------------------------ org hierarchy
  /**
   * {@code VW_ORG_HIERARCHY}: {@code WITH RECURSIVE} from the roots over <b>all</b> employees, the
   * active filter applied in the outer select (Oracle applies the view {@code WHERE} after {@code
   * CONNECT BY}). A revisited {@code emp_id} is emitted once with {@code cycle = true} and the walk
   * stops there (legacy would raise ORA-01436).
   */
  public Rows<OrgHierarchyRow> orgHierarchy(
      @Nullable Long rootEmpId, @Nullable Integer maxLevel, int limit, int offset) {
    String sql =
        "with recursive org as ("
            + " select e.emp_id, e.emp_number, e.first_name || ' ' || e.last_name as full_name,"
            + " e.manager_emp_id, e.job_id, e.dept_id, e.employment_status, 1 as org_level,"
            + " e.first_name || ' ' || e.last_name as org_path, array[e.emp_id] as visited,"
            + " false as cycle"
            + " from employees e"
            + " where (:rootEmpId::bigint is null and e.manager_emp_id is null)"
            + " or e.emp_id = :rootEmpId"
            + " union all"
            + " select c.emp_id, c.emp_number, c.first_name || ' ' || c.last_name,"
            + " c.manager_emp_id, c.job_id, c.dept_id, c.employment_status, p.org_level + 1,"
            + " p.org_path || ' > ' || c.first_name || ' ' || c.last_name,"
            + " p.visited || c.emp_id, c.emp_id = any (p.visited)"
            + " from employees c join org p on c.manager_emp_id = p.emp_id"
            + " where not p.cycle and (:maxLevel::int is null or p.org_level < :maxLevel))"
            + " select count(*) over () as total, o.emp_id, o.emp_number, o.full_name,"
            + " j.job_title, d.dept_name, o.manager_emp_id,"
            + " m.first_name || ' ' || m.last_name as manager_name, o.org_level, o.org_path,"
            + " not exists (select 1 from employees k where k.manager_emp_id = o.emp_id"
            + "   and not (k.emp_id = any (o.visited))) as is_leaf,"
            + " (select count(*) from employees k where k.manager_emp_id = o.emp_id"
            + "   and k.employment_status = 'ACTIVE' and k.active_flag = 'Y') as direct_reports,"
            + " o.cycle"
            + " from org o"
            + " join job_titles j on o.job_id = j.job_id"
            + " join departments d on o.dept_id = d.dept_id"
            + " left join employees m on o.manager_emp_id = m.emp_id"
            + " where o.employment_status = 'ACTIVE'"
            + " order by o.org_path, o.emp_id"
            + WINDOW;
    return page(
        sql,
        new MapSqlParameterSource("rootEmpId", rootEmpId)
            .addValue("maxLevel", maxLevel)
            .addValue("limit", limit)
            .addValue("offset", offset),
        (rs, i) ->
            new OrgHierarchyRow(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("full_name"),
                rs.getString("job_title"),
                rs.getString("dept_name"),
                nullableLong(rs, "manager_emp_id"),
                rs.getString("manager_name"),
                rs.getInt("org_level"),
                rs.getString("org_path"),
                rs.getBoolean("is_leaf"),
                rs.getInt("direct_reports"),
                rs.getBoolean("cycle")));
  }

  // ------------------------------------------------------------------ employee compensation
  private static final String COMPENSATION_FROM =
      " from employees e"
          + " join departments d on e.dept_id = d.dept_id"
          + " join job_titles j on e.job_id = j.job_id"
          + " join job_grades g on j.grade_id = g.grade_id"
          + " join salary_records sr on e.emp_id = sr.emp_id and sr.active_flag = 'Y'"
          + "   and sr.end_date is null"
          + " where e.employment_status = 'ACTIVE' and e.active_flag = 'Y'"
          + " and (:deptId::bigint is null or e.dept_id = :deptId)"
          + " and (:gradeId::int is null or g.grade_id = :gradeId)";

  public Rows<EmployeeCompensationRow> employeeCompensation(
      LocalDate asOf, @Nullable Long deptId, @Nullable Integer gradeId, int limit, int offset) {
    String sql =
        "select count(*) over () as total, e.emp_id, e.emp_number,"
            + " e.first_name || ' ' || e.last_name as full_name, e.dept_id, d.dept_name,"
            + " j.job_title, g.grade_id, g.grade_code, sr.base_salary, sr.currency_code,"
            + " sr.pay_frequency, sr.effective_date,"
            + " "
            + tenureYears(":asOf::date", "sr.effective_date")
            + " as years_in_grade,"
            + " g.min_salary, g.max_salary,"
            + " round(sr.base_salary / ((g.min_salary + g.max_salary) / 2), 4) as compa_ratio"
            + COMPENSATION_FROM
            + " order by e.emp_id, sr.effective_date"
            + WINDOW;
    return page(
        sql,
        params(asOf, limit, offset).addValue("deptId", deptId).addValue("gradeId", gradeId),
        (rs, i) ->
            new EmployeeCompensationRow(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("full_name"),
                rs.getLong("dept_id"),
                rs.getString("dept_name"),
                rs.getString("job_title"),
                rs.getInt("grade_id"),
                rs.getString("grade_code"),
                ReportingDtos.money(rs.getBigDecimal("base_salary")),
                rs.getString("currency_code"),
                rs.getString("pay_frequency"),
                rs.getObject("effective_date", LocalDate.class),
                ReportingDtos.years(rs.getBigDecimal("years_in_grade")),
                ReportingDtos.money(rs.getBigDecimal("min_salary")),
                ReportingDtos.money(rs.getBigDecimal("max_salary")),
                ReportingDtos.rate(rs.getBigDecimal("compa_ratio"))));
  }

  /** {@code PKG_REPORTING.compensation_summary}. */
  public List<CompensationByDepartment> compensationByDepartment(
      @Nullable Long deptId, @Nullable Integer gradeId) {
    String sql =
        "select d.dept_id, d.dept_name, count(*) as headcount, avg(sr.base_salary) as avg_salary,"
            + " min(sr.base_salary) as min_salary, max(sr.base_salary) as max_salary,"
            + " sum(sr.base_salary) as total_payroll"
            + COMPENSATION_FROM
            + " group by d.dept_id, d.dept_name order by d.dept_name, d.dept_id";
    return jdbc.query(
        sql,
        new MapSqlParameterSource("deptId", deptId).addValue("gradeId", gradeId),
        (rs, i) ->
            new CompensationByDepartment(
                rs.getLong("dept_id"),
                rs.getString("dept_name"),
                rs.getInt("headcount"),
                ReportingDtos.money(rs.getBigDecimal("avg_salary")),
                ReportingDtos.money(rs.getBigDecimal("min_salary")),
                ReportingDtos.money(rs.getBigDecimal("max_salary")),
                ReportingDtos.money(rs.getBigDecimal("total_payroll"))));
  }

  // ------------------------------------------------------------------ leave summary
  private static final String LEAVE_FROM =
      " from leave_balances lb"
          + " join employees e on lb.emp_id = e.emp_id"
          + " join departments d on e.dept_id = d.dept_id"
          + " join leave_types lt on lb.leave_type_id = lt.leave_type_id"
          + " where lb.calendar_year = :year"
          + " and e.employment_status = 'ACTIVE' and e.active_flag = 'Y'"
          + " and (:deptId::bigint is null or e.dept_id = :deptId)"
          + " and (:leaveTypeId::int is null or lb.leave_type_id = :leaveTypeId)";

  public Rows<LeaveSummaryRow> leaveSummary(
      int year, @Nullable Long deptId, @Nullable Integer leaveTypeId, int limit, int offset) {
    String sql =
        "select count(*) over () as total, e.emp_id, e.emp_number,"
            + " e.first_name || ' ' || e.last_name as emp_name, d.dept_name,"
            + " lt.leave_type_id, lt.leave_type_name, lb.calendar_year,"
            + " lb.opening_balance, lb.accrued, lb.used, lb.adjustment, lb.pending,"
            + " lb.opening_balance + lb.accrued - lb.used + lb.adjustment as legacy_available,"
            + " round(lb.used * 100 / nullif(lb.opening_balance + lb.accrued, 0), 1)"
            + "   as utilization_pct"
            + LEAVE_FROM
            + " order by e.emp_id, lt.leave_type_name, lt.leave_type_id"
            + WINDOW;
    return page(
        sql,
        new MapSqlParameterSource("year", year)
            .addValue("deptId", deptId)
            .addValue("leaveTypeId", leaveTypeId)
            .addValue("limit", limit)
            .addValue("offset", offset),
        (rs, i) -> {
          BigDecimal legacy = rs.getBigDecimal("legacy_available");
          BigDecimal pending = rs.getBigDecimal("pending");
          return new LeaveSummaryRow(
              rs.getLong("emp_id"),
              rs.getString("emp_number"),
              rs.getString("emp_name"),
              rs.getString("dept_name"),
              rs.getInt("leave_type_id"),
              rs.getString("leave_type_name"),
              rs.getInt("calendar_year"),
              ReportingDtos.days(rs.getBigDecimal("opening_balance")),
              ReportingDtos.days(rs.getBigDecimal("accrued")),
              ReportingDtos.days(rs.getBigDecimal("used")),
              ReportingDtos.days(rs.getBigDecimal("adjustment")),
              ReportingDtos.days(pending),
              ReportingDtos.days(legacy.subtract(pending)),
              ReportingDtos.percent(rs.getBigDecimal("utilization_pct")),
              ReportingDtos.days(legacy));
        });
  }

  /** {@code PKG_REPORTING.leave_utilization_report}. */
  public List<LeaveUtilizationByType> leaveUtilizationByType(
      int year, @Nullable Long deptId, @Nullable Integer leaveTypeId) {
    String sql =
        "select lt.leave_type_id, lt.leave_type_name, count(distinct lb.emp_id) as employees,"
            + " sum(lb.accrued) as total_accrued, sum(lb.used) as total_used,"
            + " round(avg(lb.used * 100 / nullif(lb.opening_balance + lb.accrued, 0)), 1)"
            + "   as avg_utilization_pct"
            + LEAVE_FROM
            + " group by lt.leave_type_id, lt.leave_type_name"
            + " order by lt.leave_type_name, lt.leave_type_id";
    return jdbc.query(
        sql,
        new MapSqlParameterSource("year", year)
            .addValue("deptId", deptId)
            .addValue("leaveTypeId", leaveTypeId),
        (rs, i) ->
            new LeaveUtilizationByType(
                rs.getInt("leave_type_id"),
                rs.getString("leave_type_name"),
                rs.getInt("employees"),
                ReportingDtos.days(rs.getBigDecimal("total_accrued")),
                ReportingDtos.days(rs.getBigDecimal("total_used")),
                ReportingDtos.percent(rs.getBigDecimal("avg_utilization_pct"))));
  }

  // ------------------------------------------------------------------ payroll latest
  /** Latest period that has a run in APPROVED / PAID; null when none. */
  @Nullable
  public Long latestPaidPeriod() {
    return jdbc.queryForObject(
        "select max(pr.period_id) from payroll_runs pr where pr.status in ('APPROVED', 'PAID')",
        new MapSqlParameterSource(),
        Long.class);
  }

  public boolean periodExists(long periodId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from pay_periods where period_id = :p",
            new MapSqlParameterSource("p", periodId),
            Integer.class);
    return n != null && n > 0;
  }

  private static final String PAYROLL_FROM =
      " from payroll_details pd"
          + " join employees e on pd.emp_id = e.emp_id"
          + " join departments d on e.dept_id = d.dept_id"
          + " join payroll_runs pr on pd.run_id = pr.run_id"
          + " join pay_periods pp on pr.period_id = pp.period_id"
          + " where pr.run_id = (select max(r2.run_id) from payroll_runs r2"
          + "   where r2.period_id = :periodId and r2.status in ('APPROVED', 'PAID'))"
          + " and pd.status <> 'ERROR'"
          + " and (:deptId::bigint is null or e.dept_id = :deptId)";

  private static final String PAYROLL_AGG =
      " sum(case when pd.element_type = 'EARNING' then pd.amount else 0 end) as gross_pay,"
          + " sum(case when pd.element_type = 'TAX' then abs(pd.amount) else 0 end) as total_taxes,"
          + " sum(case when pd.element_type in ('DEDUCTION', 'BENEFIT') then abs(pd.amount)"
          + "   else 0 end) as total_deductions,"
          + " sum(pd.amount) as net_pay";

  public Rows<PayrollLatestRow> payrollLatest(
      long periodId, @Nullable Long deptId, int limit, int offset) {
    String sql =
        "select count(*) over () as total, pd.emp_id, e.emp_number,"
            + " e.first_name || ' ' || e.last_name as emp_name, d.dept_name,"
            + " pp.period_id, pp.period_name, pp.pay_date, pr.run_id, pr.run_type, pr.status,"
            + PAYROLL_AGG
            + PAYROLL_FROM
            + " group by pd.emp_id, e.emp_number, e.first_name, e.last_name, d.dept_name,"
            + " pp.period_id, pp.period_name, pp.pay_date, pr.run_id, pr.run_type, pr.status"
            + " order by pd.emp_id"
            + WINDOW;
    return page(
        sql,
        new MapSqlParameterSource("periodId", periodId)
            .addValue("deptId", deptId)
            .addValue("limit", limit)
            .addValue("offset", offset),
        (rs, i) ->
            new PayrollLatestRow(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("emp_name"),
                rs.getString("dept_name"),
                rs.getLong("period_id"),
                rs.getString("period_name"),
                rs.getObject("pay_date", LocalDate.class),
                rs.getLong("run_id"),
                rs.getString("run_type"),
                rs.getString("status"),
                ReportingDtos.money(rs.getBigDecimal("gross_pay")),
                ReportingDtos.money(rs.getBigDecimal("total_taxes")),
                ReportingDtos.money(rs.getBigDecimal("total_deductions")),
                ReportingDtos.money(rs.getBigDecimal("net_pay"))));
  }

  /** {@code PKG_REPORTING.payroll_summary_report(p_period_id)} over the same latest run. */
  public PayrollSummary payrollSummary(@Nullable Long periodId, @Nullable Long deptId) {
    if (periodId == null) {
      return new PayrollSummary(null, 0, "0.00", "0.00", "0.00", "0.00", "0.00");
    }
    String sql =
        "select count(*) as employee_count, coalesce(sum(gross_pay), 0) as total_gross,"
            + " coalesce(sum(total_taxes), 0) as total_taxes,"
            + " coalesce(sum(total_deductions), 0) as total_deductions,"
            + " coalesce(sum(net_pay), 0) as total_net, coalesce(avg(net_pay), 0) as avg_net"
            + " from (select pd.emp_id,"
            + PAYROLL_AGG
            + PAYROLL_FROM
            + " group by pd.emp_id) x";
    return jdbc.queryForObject(
        sql,
        new MapSqlParameterSource("periodId", periodId).addValue("deptId", deptId),
        (rs, i) ->
            new PayrollSummary(
                periodId,
                rs.getInt("employee_count"),
                ReportingDtos.money(rs.getBigDecimal("total_gross")),
                ReportingDtos.money(rs.getBigDecimal("total_taxes")),
                ReportingDtos.money(rs.getBigDecimal("total_deductions")),
                ReportingDtos.money(rs.getBigDecimal("total_net")),
                ReportingDtos.money(rs.getBigDecimal("avg_net"))));
  }

  // ------------------------------------------------------------------ pending approvals
  /**
   * {@code VW_PENDING_APPROVALS}: {@code UNION ALL} of PENDING leave requests and performance
   * reviews awaiting the manager ({@code MANAGER_REVIEW}, the only "submitted" state of {@code
   * CHK_REVIEW_STATUS}).
   */
  private static final String PENDING_UNION =
      "select 'LEAVE' as item_type, lr.request_id as item_id, e.emp_id, e.emp_number,"
          + " e.first_name || ' ' || e.last_name as emp_name, d.dept_name, e.dept_id,"
          + " lr.approver_emp_id as approver_emp_id,"
          + " a.first_name || ' ' || a.last_name as approver_name,"
          + " lr.created_date::date as submitted_date,"
          + " lt.leave_type_name || ' ' || to_char(lr.start_date, 'YYYY-MM-DD') || '..'"
          + "   || to_char(lr.end_date, 'YYYY-MM-DD') || ' (' || trim_scale(lr.total_days)::text"
          + "   || 'd)' as detail"
          + " from leave_requests lr"
          + " join employees e on lr.emp_id = e.emp_id"
          + " join departments d on e.dept_id = d.dept_id"
          + " join leave_types lt on lr.leave_type_id = lt.leave_type_id"
          + " left join employees a on lr.approver_emp_id = a.emp_id"
          + " where lr.status = 'PENDING'"
          + " union all"
          + " select 'REVIEW', pr.review_id, e.emp_id, e.emp_number,"
          + " e.first_name || ' ' || e.last_name, d.dept_name, e.dept_id,"
          + " pr.reviewer_emp_id, a.first_name || ' ' || a.last_name,"
          + " pr.created_date::date, rc.cycle_name"
          + " from performance_reviews pr"
          + " join employees e on pr.emp_id = e.emp_id"
          + " join departments d on e.dept_id = d.dept_id"
          + " join review_cycles rc on pr.cycle_id = rc.cycle_id"
          + " left join employees a on pr.reviewer_emp_id = a.emp_id"
          + " where pr.status = 'MANAGER_REVIEW'";

  private static final String PENDING_FILTER =
      " where (:itemType::varchar is null or p.item_type = :itemType)"
          + " and (:approverEmpId::bigint is null or p.approver_emp_id = :approverEmpId)"
          + " and (:deptId::bigint is null or p.dept_id = :deptId)";

  public Rows<PendingApprovalRow> pendingApprovals(
      LocalDate asOf,
      @Nullable String itemType,
      @Nullable Long approverEmpId,
      @Nullable Long deptId,
      int limit,
      int offset) {
    String sql =
        "select count(*) over () as total, p.*,"
            + " greatest(0, :asOf::date - p.submitted_date) as days_pending"
            + " from ("
            + PENDING_UNION
            + ") p"
            + PENDING_FILTER
            + " order by p.submitted_date, p.item_type, p.item_id"
            + WINDOW;
    return page(
        sql,
        params(asOf, limit, offset)
            .addValue("itemType", itemType)
            .addValue("approverEmpId", approverEmpId)
            .addValue("deptId", deptId),
        (rs, i) ->
            new PendingApprovalRow(
                rs.getString("item_type"),
                rs.getLong("item_id"),
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("emp_name"),
                rs.getString("dept_name"),
                nullableLong(rs, "approver_emp_id"),
                rs.getString("approver_name"),
                rs.getObject("submitted_date", LocalDate.class),
                rs.getInt("days_pending"),
                rs.getString("detail")));
  }

  public PendingSummary pendingSummary(
      @Nullable String itemType, @Nullable Long approverEmpId, @Nullable Long deptId) {
    String sql =
        "select count(*) filter (where p.item_type = 'LEAVE') as leave,"
            + " count(*) filter (where p.item_type = 'REVIEW') as review from ("
            + PENDING_UNION
            + ") p"
            + PENDING_FILTER;
    return jdbc.queryForObject(
        sql,
        new MapSqlParameterSource("itemType", itemType)
            .addValue("approverEmpId", approverEmpId)
            .addValue("deptId", deptId),
        (rs, i) -> new PendingSummary(rs.getInt("leave"), rs.getInt("review")));
  }

  // ------------------------------------------------------------------ lookups for filters
  public boolean activeEmployee(long empId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from employees where emp_id = :id and employment_status = 'ACTIVE'"
                + " and active_flag = 'Y'",
            new MapSqlParameterSource("id", empId),
            Integer.class);
    return n != null && n > 0;
  }

  public boolean activeDepartment(long deptId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from departments where dept_id = :id and active_flag = 'Y'",
            new MapSqlParameterSource("id", deptId),
            Integer.class);
    return n != null && n > 0;
  }

  // ------------------------------------------------------------------ plumbing
  private static MapSqlParameterSource params(LocalDate asOf, int limit, int offset) {
    return new MapSqlParameterSource("asOf", asOf)
        .addValue("limit", limit)
        .addValue("offset", offset);
  }

  @Nullable
  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  private <T> Rows<T> page(
      String sql, MapSqlParameterSource params, org.springframework.jdbc.core.RowMapper<T> mapper) {
    long[] total = {0};
    List<T> rows =
        jdbc.query(
            sql,
            params,
            (rs, i) -> {
              total[0] = rs.getLong("total");
              return mapper.mapRow(rs, i);
            });
    if (rows.isEmpty() && params.getValue("offset") instanceof Integer off && off > 0) {
      total[0] = countBeyondWindow(sql, params);
    }
    return new Rows<>(rows, total[0]);
  }

  /** Empty page past the end: re-run without the window to get the total. */
  private long countBeyondWindow(String sql, MapSqlParameterSource params) {
    String unpaged = sql.substring(0, sql.lastIndexOf(WINDOW));
    Long n = jdbc.queryForObject("select count(*) from (" + unpaged + ") w", params, Long.class);
    return n == null ? 0 : n;
  }
}
