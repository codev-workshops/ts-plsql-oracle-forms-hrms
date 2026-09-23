package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 reporting scenarios (TEST_STRATEGY.md §5 row 5, COMPONENT_MAPPING.md §9): each of the six
 * report families of contracts/p5-reporting-decommission is compared row-for-row with the Oracle
 * view it replaces ({@code plsql/views/VW_*.sql}, the read side of {@code PKG_REPORTING}). The
 * legacy leg selects from the view at {@code as_of = 2024-06-30} on the frozen seed; the recorded
 * expectations are the rows of {@code tests/golden/views-baseline.csv} (see tests/golden/README.md
 * for how that file was produced without an Oracle instance).
 *
 * <p>Number rendering: the target emits money / days as fixed-scale strings ({@code "450000.00"},
 * {@code "9.50"}); the legacy binds are {@code TO_CHAR}'d with the same scale so both legs project
 * identically (RestRunner only normalises JSON numbers, not strings).
 *
 * <p>Documented divergences: {@code compaRatio} is {@code ROUND(base / midpoint, 4)} in the
 * contract where the view returns a one-decimal percentage; {@code available} subtracts {@code
 * pending} (contract) while the view does not – the view figure is exposed as JSON-only {@code
 * legacyAvailable}. {@code orgPath} drops the leading {@code " > "} of {@code SYS_CONNECT_BY_PATH}.
 *
 * <p>All scenarios are pure reads on the seed population and therefore run before {@code
 * performance.*} (generate-reviews adds MANAGER_REVIEW rows to the pending-approvals view), {@code
 * payroll.*} and {@code employee.*} (ScenarioRegistry order).
 */
final class ReportingScenarios {

  private ReportingScenarios() {}

  static final String MODULE = "reporting";

  /** Report evaluation date: the seed baseline date of tests/golden/views-baseline.csv. */
  static final String AS_OF = "2024-06-30";

  /** emp 1, EXECUTIVE (REPORTS:VIEW + PAYROLL:VIEW). */
  private static final String EXEC = PerformanceScenarios.ADMIN;

  static List<Scenario> all() {
    return List.of(
        // --- employee directory (VW_ACTIVE_EMPLOYEES) ------------------------------------------
        new Scenario(
            "reporting.employee-directory.seed",
            MODULE,
            view(
                "select count(*), count(*), min(emp_number),"
                    + " to_char(max(decode(emp_id, 1, tenure_years)), 'FM990.0')"
                    + " into :page.totalElements, :summary.totalHeadcount, :content[0].empNumber,"
                    + " :content[0].tenureYears from vw_active_employees",
                List.of(
                    "page.totalElements",
                    "summary.totalHeadcount",
                    "content[0].empNumber",
                    "content[0].tenureYears")),
            get("/api/reports/employee-directory?asOf=" + AS_OF, EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "23",
                    "summary.totalHeadcount", "23",
                    "content[0].empNumber", "EMP-000001",
                    "content[0].tenureYears", "14.2"))),
        new Scenario(
            "reporting.employee-directory.dept-filter",
            MODULE,
            view(
                "select count(*), min(emp_number) into :page.totalElements, :content[0].empNumber"
                    + " from vw_active_employees where dept_id = 20",
                List.of("page.totalElements", "content[0].empNumber")),
            get("/api/reports/employee-directory?asOf=" + AS_OF + "&deptId=20", EXEC),
            // dept 20 (Engineering): emp 2 and 20..24 are ACTIVE on the seed
            Outcome.ok(Map.of("page.totalElements", "6", "content[0].empNumber", "EMP-000002"))),
        new Scenario(
            "reporting.employee-directory.invalid-dept",
            MODULE,
            // PKG_EMPLOYEE.validate_dept is body-private; same check inline
            plsql(
                "declare v_n number; begin select count(*) into v_n from departments"
                    + " where dept_id = 2 and active_flag = 'Y'; if v_n = 0 then"
                    + " raise_application_error(-20003, 'Invalid or inactive department: 2');"
                    + " end if; end;",
                List.of()),
            get("/api/reports/employee-directory?asOf=" + AS_OF + "&deptId=2", EXEC),
            Outcome.error("-20003")),
        // --- org hierarchy (VW_ORG_HIERARCHY, CONNECT BY -> WITH RECURSIVE) ----------------------
        new Scenario(
            "reporting.org-hierarchy.seed",
            MODULE,
            view(
                "select count(*), max(decode(emp_id, 1, org_level)),"
                    + " ltrim(max(decode(emp_id, 1, org_path)), ' > '),"
                    + " decode(max(decode(emp_id, 1, is_leaf)), 1, 'true', 'false')"
                    + " into :page.totalElements, :content[0].orgLevel, :content[0].orgPath,"
                    + " :content[0].isLeaf from vw_org_hierarchy",
                List.of(
                    "page.totalElements",
                    "content[0].orgLevel",
                    "content[0].orgPath",
                    "content[0].isLeaf")),
            get("/api/reports/org-hierarchy?asOf=" + AS_OF, EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "23",
                    "content[0].orgLevel", "1",
                    "content[0].orgPath", "JAMES RICHARDSON",
                    "content[0].isLeaf", "false"))),
        new Scenario(
            "reporting.org-hierarchy.subtree",
            MODULE,
            view(
                // START WITH emp 10: the subtree root is level 1 again (contract), as CONNECT BY
                "select count(*), min(emp_number), min(org_level) - 1"
                    + " into :page.totalElements, :content[0].empNumber, :content[0].orgLevel"
                    + " from vw_org_hierarchy"
                    + " where emp_id = 10 or org_path like '% > PATRICIA WILLIAMS > %'",
                List.of("page.totalElements", "content[0].empNumber", "content[0].orgLevel")),
            get("/api/reports/org-hierarchy?asOf=" + AS_OF + "&rootEmpId=10", EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "3",
                    "content[0].empNumber", "EMP-000010",
                    "content[0].orgLevel", "1"))),
        new Scenario(
            "reporting.org-hierarchy.invalid-root",
            MODULE,
            plsql(
                "begin if not pkg_employee.validate_employee(999999) then"
                    + " raise_application_error(-20001, 'Employee not found: 999999'); end if; end;",
                List.of()),
            get("/api/reports/org-hierarchy?rootEmpId=999999", EXEC),
            Outcome.error("-20001")),
        // --- employee compensation (VW_EMPLOYEE_COMPENSATION) -----------------------------------
        new Scenario(
            "reporting.employee-compensation.seed",
            MODULE,
            view(
                "select count(*), min(emp_number),"
                    + " to_char(max(decode(emp_id, 1, base_salary)), 'FM999999990.00'),"
                    + " to_char(max(decode(emp_id, 1, compa_ratio)) / 100, 'FM0.0000')"
                    + " into :page.totalElements, :content[0].empNumber, :content[0].baseSalary,"
                    + " :content[0].compaRatio from vw_employee_compensation",
                List.of(
                    "page.totalElements",
                    "content[0].empNumber",
                    "content[0].baseSalary",
                    "content[0].compaRatio")),
            get("/api/reports/employee-compensation?asOf=" + AS_OF, EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "23",
                    "content[0].empNumber", "EMP-000001",
                    "content[0].baseSalary", "450000.00",
                    "content[0].compaRatio", "1.0000"))),
        // --- leave summary (VW_LEAVE_SUMMARY) ---------------------------------------------------
        new Scenario(
            "reporting.leave-summary.seed-year",
            MODULE,
            view(
                // first row = emp 2 / PTO (balance 9001: pending 0, so available == legacy)
                "select count(*), min(emp_number),"
                    + " to_char(max(case when emp_id = 2 and leave_type_name = 'Paid Time Off'"
                    + " then accrued end), 'FM999990.00'),"
                    + " to_char(max(case when emp_id = 2 and leave_type_name = 'Paid Time Off'"
                    + " then available end), 'FM999990.00'),"
                    + " to_char(max(case when emp_id = 2 and leave_type_name = 'Paid Time Off'"
                    + " then available end), 'FM999990.00'),"
                    + " to_char(max(case when emp_id = 2 and leave_type_name = 'Paid Time Off'"
                    + " then utilization_pct end), 'FM990.0')"
                    + " into :page.totalElements, :content[0].empNumber, :content[0].accrued,"
                    + " :content[0].legacyAvailable, :content[0].available, :content[0].utilizationPct"
                    + " from vw_leave_summary",
                List.of(
                    "page.totalElements",
                    "content[0].empNumber",
                    "content[0].accrued",
                    "content[0].legacyAvailable",
                    "content[0].available",
                    "content[0].utilizationPct")),
            get("/api/reports/leave-summary?asOf=" + AS_OF + "&year=2024", EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "11",
                    "content[0].empNumber", "EMP-000002",
                    "content[0].accrued", "7.50",
                    "content[0].legacyAvailable", "9.50",
                    "content[0].available", "9.50",
                    "content[0].utilizationPct", "24.0"))),
        // --- payroll latest (VW_PAYROLL_LATEST: run 9001 / MAY-2024) -----------------------------
        new Scenario(
            "reporting.payroll-latest.seed",
            MODULE,
            view(
                "select count(*), to_char(sum(gross_pay), 'FM999999990.00'),"
                    + " to_char(sum(net_pay), 'FM999999990.00'), min(period_name), min(emp_number),"
                    + " to_char(max(decode(emp_id, 2, net_pay)), 'FM999999990.00')"
                    + " into :summary.employeeCount, :summary.totalGross, :summary.totalNet,"
                    + " :content[0].periodName, :content[0].empNumber, :content[0].netPay"
                    + " from vw_payroll_latest",
                List.of(
                    "summary.employeeCount",
                    "summary.totalGross",
                    "summary.totalNet",
                    "content[0].periodName",
                    "content[0].empNumber",
                    "content[0].netPay")),
            get("/api/reports/payroll-latest", EXEC),
            Outcome.ok(
                Map.of(
                    "summary.employeeCount", "4",
                    "summary.totalGross", "47916.67",
                    "summary.totalNet", "28697.72",
                    "content[0].periodName", "MAY-2024",
                    "content[0].empNumber", "EMP-000002",
                    "content[0].netPay", "11906.25"))),
        // --- pending approvals (VW_PENDING_APPROVALS: 3 LEAVE + 2 PERFORMANCE on the seed) ------
        new Scenario(
            "reporting.pending-approvals.seed",
            MODULE,
            view(
                // first row in contract order (submittedDate, itemType, itemId): review 5001
                // (2024-06-10) precedes leave 1001 (2024-06-20); PERFORMANCE is REVIEW in the API
                "select count(*), sum(decode(approval_type, 'LEAVE', 1, 0)),"
                    + " sum(decode(approval_type, 'PERFORMANCE', 1, 0)),"
                    + " min(decode(approval_type, 'PERFORMANCE', 'REVIEW', approval_type))"
                    + " keep (dense_rank first order by request_date, approval_type, item_id),"
                    + " min(item_id)"
                    + " keep (dense_rank first order by request_date, approval_type, item_id)"
                    + " into :page.totalElements, :summary.leave, :summary.review,"
                    + " :content[0].itemType, :content[0].itemId from vw_pending_approvals",
                List.of(
                    "page.totalElements",
                    "summary.leave",
                    "summary.review",
                    "content[0].itemType",
                    "content[0].itemId")),
            get("/api/reports/pending-approvals?asOf=" + AS_OF, EXEC),
            Outcome.ok(
                Map.of(
                    "page.totalElements", "5",
                    "summary.leave", "3",
                    "summary.review", "2",
                    "content[0].itemType", "REVIEW",
                    "content[0].itemId", "5001"))),
        new Scenario(
            "reporting.pending-approvals.leave-only",
            MODULE,
            view(
                "select count(*), 0 into :page.totalElements, :summary.review"
                    + " from vw_pending_approvals where approval_type = 'LEAVE'",
                List.of("page.totalElements", "summary.review")),
            get("/api/reports/pending-approvals?asOf=" + AS_OF + "&itemType=LEAVE", EXEC),
            Outcome.ok(Map.of("page.totalElements", "3", "summary.review", "0"))));
  }

  /**
   * Legacy leg: a SELECT over the Oracle view. The views read {@code SYSDATE} (TENURE_YEARS,
   * pending ages), so a live capture must run with the session clock fixed at {@link #AS_OF}
   * (tools/reconcile {@code --as-of}); the recorded expectations are taken at that date.
   */
  private static LegacyCall view(String select, List<String> outBinds) {
    return new LegacyCall(
        "begin execute immediate 'alter session set nls_date_format = ''YYYY-MM-DD'''; "
            + select
            + "; end;",
        outBinds);
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall get(String path, String auth) {
    return new RestCall("GET", path, null, auth, false, List.of());
  }
}
