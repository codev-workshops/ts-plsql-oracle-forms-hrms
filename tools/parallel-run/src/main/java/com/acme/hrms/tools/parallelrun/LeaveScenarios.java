package com.acme.hrms.tools.parallelrun;

import com.acme.hrms.tools.parallelrun.Scenario.LegacyCall;
import com.acme.hrms.tools.parallelrun.Scenario.Outcome;
import com.acme.hrms.tools.parallelrun.Scenario.RestCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2 Level-2 scenarios (TEST_STRATEGY.md §5 row 2, COMPONENT_MAPPING.md §5): PKG_LEAVE against
 * {@code /api/leave/*}. Legacy expectations are recorded from plsql/packages/PKG_LEAVE.pkb
 * (golden-oracle mode OFF, {@code legacy_source=recorded}); the integration session replays the
 * legacy leg when Oracle is attached.
 *
 * <p>Requires {@code tools/parallel-run/fixtures/leave.sql} on top of the seed: emp 2 (sarah.chen,
 * manager emp 1) gets 10 PTO days in the current year and in 2030..2034; scenarios that observe a
 * balance each use their own calendar year so their pending/used mutations do not interfere
 * (QUIRK-01: the balance *check* reads the current year, the mutation the start-date year). All
 * dates are fixed Mondays/Fridays without seed holidays; captured ids are referenced as {@code
 * {requestId}} (see {@link RestRunner}).
 *
 * <p>Documented divergences (contracts/p2-leave/error-codes.md §BUG-04/05/06) are encoded in {@link
 * #legacyOutcome}: the contract outcome is what the target must return, the legacy column expects
 * the old behaviour, and the row is still {@code PASS}.
 */
final class LeaveScenarios {

  private LeaveScenarios() {}

  static final String MODULE = "leave";

  /** emp 2, STAFF – the requester. */
  static final String EMP_2 = ScenarioRegistry.USER;

  /** emp 1, EXECUTIVE – emp 2's manager, hence designated approver of her requests. */
  static final String MANAGER = PerformanceScenarios.ADMIN;

  static final int PTO = 1;

  /** fixtures/leave.sql: active type with min_tenure_days = 36500. */
  static final int TENURE_GATED_TYPE = 99;

  /**
   * Batch endpoints declared by the P2 contract but not mounted by any target. P5 mounts the
   * accrual and carryover jobs asynchronously ({@code POST /api/admin/leave/accrual|carryover},
   * {@code 202} + job row, polled on {@link #JOB_PATH}; the P2 paths are {@code 301} aliases) and
   * drops {@code carryover/expire} from the contract, so only the BUG-04 row keeps the P2 outcome
   * {@code 404} without a body ({@link #NOT_MOUNTED}) and is reported {@code DEFERRED}; its legacy
   * leg is still recorded and its P5 balances are kept in {@link #P5_CONTRACT} (asserted in Level
   * 1, backend/leave LeaveAccrualJobTest).
   */
  static final Set<String> DEFERRED_TO_P5 = Set.of("leave.batch.carryover.expire.bug-04");

  static final Outcome NOT_MOUNTED = Outcome.error("HTTP_404");

  /** Job status resource of the P5 leave batch API; polled by the REST runner until settled. */
  static final String JOB_PATH = "/api/admin/leave/jobs/{jobId}";

  /** P5 target outcome of the deferred expire scenario on the seed year (balance 9001). */
  static final Map<String, Outcome> P5_CONTRACT =
      Map.of(
          // BUG-04: carryover 5, used 3 → only the remaining 2 are forfeited (legacy forfeits 5)
          "leave.batch.carryover.expire.bug-04",
          Outcome.ok(Map.of("adjustment", "-2", "carryoverFromPrev", "0")));

  static List<Scenario> all() {
    // --- submit -----------------------------------------------------------------------------
    RestCall submitOk = submit(PTO, "2030-03-04", "2030-03-08");
    RestCall overlapSetup = submit(PTO, "2030-03-11", "2030-03-15");
    RestCall amHalfDay = halfDay(PTO, "2030-05-06", "AM");
    // --- lifecycle: status-only scenarios use distinct 2030 weeks; balance-observing ones own a
    // whole calendar year (2031..2034) so the projected pending/used are order-independent -----
    RestCall approve = decide("approve", Map.of("comments", "ok"));
    RestCall reject = decide("reject", Map.of("comments", "no"));
    RestCall cancel = call("POST", "/api/leave/requests/{requestId}/cancel", Map.of(), EMP_2);

    return List.of(
        new Scenario(
            "leave.submit.ok",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-03-04', date '2030-03-08', 'N', null")
                    + "select status, total_days into :status, :totalDays from leave_requests"
                    + " where request_id = v_id; end;",
                List.of("status", "totalDays")),
            submitOk,
            Outcome.ok(Map.of("status", "PENDING", "totalDays", "5"))),
        new Scenario(
            "leave.submit.overlap",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-03-11', date '2030-03-15', 'N', null")
                    + "v_id := pkg_leave.submit_leave_request(2, 1, date '2030-03-13',"
                    + " date '2030-03-13', 'N', null, 'Parallel run', :user); end;",
                List.of()),
            submit(PTO, "2030-03-13", "2030-03-13").withSetup(List.of(overlapSetup)),
            Outcome.error("-20202")),
        new Scenario(
            "leave.submit.insufficient-balance",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-03-18', date '2030-04-12', 'N', null") + "end;",
                List.of()),
            submit(PTO, "2030-03-18", "2030-04-12"), // 20 business days > 10 available
            Outcome.error("-20201")),
        new Scenario(
            "leave.submit.invalid-leave-type",
            MODULE,
            plsql(
                submitPlsql("2, 999, date '2030-04-15', date '2030-04-19', 'N', null") + "end;",
                List.of()),
            submit(999, "2030-04-15", "2030-04-19"),
            Outcome.error("-20203")),
        new Scenario(
            "leave.submit.tenure-not-met",
            MODULE,
            plsql(
                submitPlsql("2, 99, date '2030-04-15', date '2030-04-19', 'N', null") + "end;",
                List.of()),
            submit(TENURE_GATED_TYPE, "2030-04-15", "2030-04-19"),
            Outcome.error("-20203")),
        new Scenario(
            "leave.submit.date-order",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-04-26', date '2030-04-22', 'N', null") + "end;",
                List.of()),
            submit(PTO, "2030-04-26", "2030-04-22"),
            Outcome.error("-20210")),
        new Scenario(
            "leave.submit.too-far-in-past",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2024-01-08', date '2024-01-12', 'N', null") + "end;",
                List.of()),
            submit(PTO, "2024-01-08", "2024-01-12"),
            Outcome.error("-20211")),
        new Scenario(
            "leave.submit.no-business-days",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-03-02', date '2030-03-03', 'N', null") + "end;",
                List.of()),
            submit(PTO, "2030-03-02", "2030-03-03"), // Sat + Sun
            Outcome.error("-20212")),
        new Scenario(
            "leave.submit.half-day.am-pm-same-day.bug-06",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-05-06', date '2030-05-06', 'Y', 'AM'")
                    + "v_id := pkg_leave.submit_leave_request(2, 1, date '2030-05-06',"
                    + " date '2030-05-06', 'Y', 'PM', 'Parallel run', :user); end;",
                List.of()),
            halfDay(PTO, "2030-05-06", "PM").withSetup(List.of(amHalfDay)),
            // BUG-06: legacy check_leave_overlap rejects the PM half of the same day (-20202)
            Outcome.ok(Map.of("status", "PENDING"))),
        new Scenario(
            "leave.business-days.saturday-holiday.bug-05",
            MODULE,
            plsql(
                "begin :businessDays := pkg_leave.calculate_business_days("
                    + "date '2030-02-25', date '2030-03-01', 'HQ'); end;",
                List.of("businessDays")),
            call("GET", "/api/leave/business-days?start=2030-02-25&end=2030-03-01", null, EMP_2),
            // BUG-05: fixture holiday Sat 2030-03-02 is observed on Fri 2030-03-01 → 4 (legacy 5)
            Outcome.ok(Map.of("businessDays", "4"))),
        // --- cancel ---------------------------------------------------------------------------
        new Scenario(
            "leave.cancel.pending",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-04-29', date '2030-05-03', 'N', null")
                    + "pkg_leave.cancel_leave_request(v_id, 'Cancelled by employee', :user); "
                    + "select status into :status from leave_requests where request_id = v_id; end;",
                List.of("status")),
            cancel.withSetup(List.of(submit(PTO, "2030-04-29", "2030-05-03"))),
            Outcome.ok(Map.of("status", "CANCELLED"))),
        new Scenario(
            "leave.cancel.pending.balance-restored",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2031-03-03', date '2031-03-07', 'N', null")
                    + "pkg_leave.cancel_leave_request(v_id, 'Cancelled by employee', :user); "
                    + "select pending, used, available into :pending, :used, :available"
                    + " from leave_balances where emp_id = 2 and leave_type_id = 1"
                    + " and calendar_year = 2031; end;",
                List.of("pending", "used", "available")),
            balances(2031).withSetup(List.of(submit(PTO, "2031-03-03", "2031-03-07"), cancel)),
            Outcome.ok(Map.of("pending", "0", "used", "0", "available", "10"))),
        new Scenario(
            "leave.cancel.approved.balance-restored",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2032-03-01', date '2032-03-05', 'N', null")
                    + "pkg_leave.approve_leave_request(v_id, 1, 'ok', :user); "
                    + "pkg_leave.cancel_leave_request(v_id, 'Cancelled by employee', :user); "
                    + "select pending, used, available into :pending, :used, :available"
                    + " from leave_balances where emp_id = 2 and leave_type_id = 1"
                    + " and calendar_year = 2032; end;",
                List.of("pending", "used", "available")),
            balances(2032)
                .withSetup(List.of(submit(PTO, "2032-03-01", "2032-03-05"), approve, cancel)),
            Outcome.ok(Map.of("pending", "0", "used", "0", "available", "10"))),
        new Scenario(
            "leave.cancel.cancelled.invalid-status",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-05-13', date '2030-05-17', 'N', null")
                    + "pkg_leave.cancel_leave_request(v_id, 'x', :user); "
                    + "pkg_leave.cancel_leave_request(v_id, 'x', :user); end;",
                List.of()),
            cancel.withSetup(List.of(submit(PTO, "2030-05-13", "2030-05-17"), cancel)),
            Outcome.error("-20204")),
        // --- approve / reject -------------------------------------------------------------------
        new Scenario(
            "leave.approve.ok",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-05-20', date '2030-05-24', 'N', null")
                    + "pkg_leave.approve_leave_request(v_id, 1, 'ok', :user); "
                    + "select status, approver_emp_id into :status, :approverEmpId"
                    + " from leave_requests where request_id = v_id; end;",
                List.of("status", "approverEmpId")),
            approve.withSetup(List.of(submit(PTO, "2030-05-20", "2030-05-24"))),
            Outcome.ok(Map.of("status", "APPROVED", "approverEmpId", "1"))),
        new Scenario(
            "leave.approve.moves-pending-to-used",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2033-03-07', date '2033-03-11', 'N', null")
                    + "pkg_leave.approve_leave_request(v_id, 1, 'ok', :user); "
                    + "select pending, used into :pending, :used from leave_balances"
                    + " where emp_id = 2 and leave_type_id = 1 and calendar_year = 2033; end;",
                List.of("pending", "used")),
            balances(2033).withSetup(List.of(submit(PTO, "2033-03-07", "2033-03-11"), approve)),
            Outcome.ok(Map.of("pending", "0", "used", "5"))),
        new Scenario(
            "leave.approve.not-pending",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-06-03', date '2030-06-07', 'N', null")
                    + "pkg_leave.approve_leave_request(v_id, 1, 'ok', :user); "
                    + "pkg_leave.approve_leave_request(v_id, 1, 'ok', :user); end;",
                List.of()),
            approve.withSetup(List.of(submit(PTO, "2030-06-03", "2030-06-07"), approve)),
            Outcome.error("-20204")),
        new Scenario(
            "leave.reject.ok.releases-pending",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2034-03-06', date '2034-03-10', 'N', null")
                    + "pkg_leave.reject_leave_request(v_id, 1, 'no', :user); "
                    + "select pending, used into :pending, :used from leave_balances"
                    + " where emp_id = 2 and leave_type_id = 1 and calendar_year = 2034; end;",
                List.of("pending", "used")),
            balances(2034).withSetup(List.of(submit(PTO, "2034-03-06", "2034-03-10"), reject)),
            Outcome.ok(Map.of("pending", "0", "used", "0"))),
        new Scenario(
            "leave.reject.status",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-06-10', date '2030-06-14', 'N', null")
                    + "pkg_leave.reject_leave_request(v_id, 1, 'no', :user); "
                    + "select status into :status from leave_requests where request_id = v_id; end;",
                List.of("status")),
            reject.withSetup(List.of(submit(PTO, "2030-06-10", "2030-06-14"))),
            Outcome.ok(Map.of("status", "REJECTED"))),
        new Scenario(
            "leave.reject.comments-required",
            MODULE,
            null, // legacy p_comments has no DEFAULT: a missing argument is a compile error, not
            // -20xxx
            decide("reject", Map.of()).withSetup(List.of(submit(PTO, "2030-06-17", "2030-06-21"))),
            Outcome.error("VALIDATION_FAILED")),
        new Scenario(
            "leave.reject.not-pending",
            MODULE,
            plsql(
                submitPlsql("2, 1, date '2030-06-24', date '2030-06-28', 'N', null")
                    + "pkg_leave.reject_leave_request(v_id, 1, 'no', :user); "
                    + "pkg_leave.reject_leave_request(v_id, 1, 'no', :user); end;",
                List.of()),
            reject.withSetup(List.of(submit(PTO, "2030-06-24", "2030-06-28"), reject)),
            Outcome.error("-20204")),
        new Scenario(
            "leave.request.not-found",
            MODULE,
            plsql("begin pkg_leave.approve_leave_request(999999, 1, 'ok', :user); end;", List.of()),
            call("POST", "/api/leave/requests/999999/approve", Map.of(), MANAGER),
            // legacy: NO_DATA_FOUND (ORA-01403) from the SELECT ... INTO; contract 404 code
            Outcome.error("LEAVE_REQUEST_NOT_FOUND")),
        // --- batch jobs on the seed year (P5 async job API; balance 9001 = emp 2 / PTO / 2024) ---
        new Scenario(
            "leave.batch.accrual.seed-year",
            MODULE,
            plsql(
                "begin pkg_leave.run_monthly_accrual(date '2024-07-31', :user); "
                    + "select accrued, available into :accrued, :available from leave_balances"
                    + " where emp_id = 2 and leave_type_id = 1 and calendar_year = 2024; end;",
                List.of("accrued", "available")),
            balances(2024)
                .withSetup(
                    List.of(
                        call(
                            "POST",
                            "/api/admin/leave/accrual",
                            Map.of("accrualDate", "2024-07-31"),
                            MANAGER),
                        call("GET", JOB_PATH, null, MANAGER))),
            // accrued 7.5 + 1.25 (PTO MONTHLY), available 5 + 8.75 - 3
            Outcome.ok(Map.of("accrued", "8.75", "available", "10.75"))),
        new Scenario(
            "leave.batch.carryover.seed-year",
            MODULE,
            plsql(
                "begin pkg_leave.process_carryover(2024, :user); "
                    + "select carryover_from_prev, opening_balance into :carryoverFromPrev,"
                    + " :openingBalance from leave_balances"
                    + " where emp_id = 2 and leave_type_id = 1 and calendar_year = 2025; end;",
                List.of("carryoverFromPrev", "openingBalance")),
            balances(2025)
                .withSetup(
                    List.of(
                        call("POST", "/api/admin/leave/carryover", Map.of("year", 2024), MANAGER),
                        call("GET", JOB_PATH, null, MANAGER))),
            // available 10.75 after the accrual above, capped at carryover_max 5
            Outcome.ok(Map.of("carryoverFromPrev", "5", "openingBalance", "5"))),
        new Scenario(
            "leave.batch.carryover.expire.bug-04",
            MODULE,
            plsql(
                "begin update leave_balances set carryover_expiry_dt = date '2024-03-31'"
                    + " where balance_id = 9001; pkg_leave.expire_carryover(:user); "
                    + "select adjustment, carryover_from_prev into :adjustment, :carryoverFromPrev"
                    + " from leave_balances where balance_id = 9001; end;",
                List.of("adjustment", "carryoverFromPrev")),
            call(
                "POST", "/api/leave/admin/carryover/expire", Map.of("asOf", "2024-04-01"), MANAGER),
            NOT_MOUNTED));
  }

  /** Legacy expectations where the contract deliberately diverges (error-codes.md BUG-04/05/06). */
  static Outcome legacyOutcome(Scenario s) {
    return switch (s.id()) {
      case "leave.submit.half-day.am-pm-same-day.bug-06" -> Outcome.error("-20202");
      case "leave.business-days.saturday-holiday.bug-05" -> Outcome.ok(Map.of("businessDays", "5"));
      case "leave.batch.carryover.expire.bug-04" ->
          Outcome.ok(Map.of("adjustment", "-5", "carryoverFromPrev", "0"));
      case "leave.request.not-found" -> Outcome.error("ORA-01403");
      default -> P5_CONTRACT.getOrDefault(s.id(), s.expect());
    };
  }

  private static String submitPlsql(String args) {
    return "declare v_id number; begin v_id := pkg_leave.submit_leave_request("
        + args
        + ", 'Parallel run', :user); ";
  }

  private static RestCall submit(int leaveTypeId, String start, String end) {
    Map<String, Object> body = new HashMap<>();
    body.put("leaveTypeId", leaveTypeId);
    body.put("startDate", start);
    body.put("endDate", end);
    body.put("halfDay", false);
    body.put("reason", "Parallel run");
    return call("POST", "/api/leave/requests", body, EMP_2);
  }

  private static RestCall halfDay(int leaveTypeId, String date, String period) {
    Map<String, Object> body = new HashMap<>();
    body.put("leaveTypeId", leaveTypeId);
    body.put("startDate", date);
    body.put("endDate", date);
    body.put("halfDay", true);
    body.put("halfDayPeriod", period);
    body.put("reason", "Parallel run");
    return call("POST", "/api/leave/requests", body, EMP_2);
  }

  private static RestCall decide(String action, Map<String, Object> body) {
    return call("POST", "/api/leave/requests/{requestId}/" + action, body, MANAGER);
  }

  private static RestCall balances(int year) {
    return call("GET", "/api/leave/balances/mine?year=" + year, null, EMP_2);
  }

  private static LegacyCall plsql(String block, List<String> outBinds) {
    return new LegacyCall(block, outBinds);
  }

  private static RestCall call(String method, String path, Map<String, Object> body, String auth) {
    return new RestCall(method, path, body, auth, false, List.of());
  }
}
