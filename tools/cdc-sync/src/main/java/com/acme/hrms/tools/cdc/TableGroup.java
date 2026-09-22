package com.acme.hrms.tools.cdc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CUTOVER_PLAN.md §2 rule 1: one owning module per table group. Tables are listed in FK order so a
 * bulk load / reverse extract can run them top-down (load) or bottom-up (delete).
 */
public enum TableGroup {
  REFERENCE(
      "reference",
      List.of(
          "locations",
          "departments",
          "job_grades",
          "job_titles",
          "holidays",
          "leave_types",
          "pay_elements",
          "tax_brackets",
          "system_parameters",
          "lookup_values")),
  EMPLOYEE(
      "employee",
      List.of(
          "employees",
          "employee_history",
          "employee_dependents",
          "emergency_contacts",
          "salary_records",
          "employee_bank_accounts",
          "employee_tax_info",
          "employee_pay_elements")),
  PAYROLL("payroll", List.of("pay_periods", "payroll_runs", "payroll_details")),
  LEAVE("leave", List.of("leave_balances", "leave_requests", "leave_accrual_log")),
  PERFORMANCE("performance", List.of("review_cycles", "performance_reviews", "performance_goals")),
  CROSS_CUTTING("cross-cutting", List.of("audit_log", "notification_queue", "user_sessions"));

  /** Primary keys, needed by the reverse extract for keyed MERGE. */
  public static final Map<String, String> PRIMARY_KEYS =
      Map.ofEntries(
          Map.entry("locations", "location_code"),
          Map.entry("departments", "dept_id"),
          Map.entry("job_grades", "grade_id"),
          Map.entry("job_titles", "job_id"),
          Map.entry("holidays", "holiday_id"),
          Map.entry("leave_types", "leave_type_id"),
          Map.entry("pay_elements", "element_id"),
          Map.entry("tax_brackets", "bracket_id"),
          Map.entry("system_parameters", "param_id"),
          Map.entry("lookup_values", "lookup_id"),
          Map.entry("employees", "emp_id"),
          Map.entry("employee_history", "hist_id"),
          Map.entry("employee_dependents", "dependent_id"),
          Map.entry("emergency_contacts", "contact_id"),
          Map.entry("salary_records", "salary_id"),
          Map.entry("employee_bank_accounts", "bank_acct_id"),
          Map.entry("employee_tax_info", "tax_info_id"),
          Map.entry("employee_pay_elements", "emp_element_id"),
          Map.entry("pay_periods", "period_id"),
          Map.entry("payroll_runs", "run_id"),
          Map.entry("payroll_details", "detail_id"),
          Map.entry("leave_balances", "balance_id"),
          Map.entry("leave_requests", "request_id"),
          Map.entry("leave_accrual_log", "accrual_id"),
          Map.entry("review_cycles", "cycle_id"),
          Map.entry("performance_reviews", "review_id"),
          Map.entry("performance_goals", "goal_id"),
          Map.entry("audit_log", "audit_id"),
          Map.entry("notification_queue", "notification_id"),
          Map.entry("user_sessions", "session_id"));

  /**
   * Columns computed by the database on both sides (Oracle virtual column, PostgreSQL {@code
   * GENERATED ALWAYS AS ... STORED}); they come back from {@code select *} but must never be
   * written by the bulk load or the reverse extract (DATA_DICTIONARY.md §3,
   * MODERNIZATION_BLUEPRINT.md §10).
   */
  public static final Map<String, Set<String>> GENERATED_COLUMNS =
      Map.of("leave_balances", Set.of("available"));

  /** Columns deliberately present only in PostgreSQL and ignored by Level 3 reconciliation. */
  public static final Map<String, Set<String>> PG_ONLY_COLUMNS =
      Map.of("salary_records", Set.of("out_of_grade_band"));

  public static boolean isPgOnly(String table, String column) {
    return PG_ONLY_COLUMNS.getOrDefault(table, Set.of()).contains(column.toLowerCase());
  }

  public static boolean isGenerated(String table, String column) {
    return GENERATED_COLUMNS.getOrDefault(table, Set.of()).contains(column.toLowerCase());
  }

  /**
   * PostgreSQL sequence backing each table's primary key (same names as the Oracle SEQ_*). Tables
   * without an entry have natural keys or externally assigned ids and need no restart.
   */
  public static final Map<String, String> SEQUENCES =
      Map.ofEntries(
          Map.entry("departments", "seq_department"),
          Map.entry("locations", "seq_location"),
          Map.entry("job_grades", "seq_job_grade"),
          Map.entry("job_titles", "seq_job_title"),
          Map.entry("holidays", "seq_holiday"),
          Map.entry("leave_types", "seq_leave_type"),
          Map.entry("pay_elements", "seq_pay_element"),
          Map.entry("tax_brackets", "seq_tax_bracket"),
          Map.entry("system_parameters", "seq_system_param"),
          Map.entry("lookup_values", "seq_lookup"),
          Map.entry("employees", "seq_employee"),
          Map.entry("employee_history", "seq_emp_history"),
          Map.entry("employee_dependents", "seq_dependent"),
          Map.entry("emergency_contacts", "seq_emergency_contact"),
          Map.entry("salary_records", "seq_salary"),
          Map.entry("employee_pay_elements", "seq_emp_pay_element"),
          Map.entry("pay_periods", "seq_pay_period"),
          Map.entry("payroll_runs", "seq_payroll_run"),
          Map.entry("payroll_details", "seq_payroll_detail"),
          Map.entry("leave_balances", "seq_leave_balance"),
          Map.entry("leave_requests", "seq_leave_request"),
          Map.entry("leave_accrual_log", "seq_leave_accrual"),
          Map.entry("review_cycles", "seq_review_cycle"),
          Map.entry("performance_reviews", "seq_perf_review"),
          Map.entry("performance_goals", "seq_perf_goal"),
          Map.entry("audit_log", "seq_audit"),
          Map.entry("notification_queue", "seq_notification"),
          Map.entry("user_sessions", "seq_user_session"));

  private final String flag;
  private final List<String> tables;

  TableGroup(String flag, List<String> tables) {
    this.flag = flag;
    this.tables = tables;
  }

  /** Proxy flag name that decides the database of record for this group. */
  public String flag() {
    return flag;
  }

  public List<String> tables() {
    return tables;
  }

  public static TableGroup byFlag(String flag) {
    for (TableGroup g : values()) {
      if (g.flag.equals(flag)) {
        return g;
      }
    }
    throw new IllegalArgumentException("unknown table group: " + flag);
  }
}
