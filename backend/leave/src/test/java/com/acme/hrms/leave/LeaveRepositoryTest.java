package com.acme.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.leave.LeaveDtos.LeaveBalance;
import com.acme.hrms.leave.LeaveDtos.LeaveRequest;
import com.acme.hrms.leave.LeaveDtos.PendingLeaveApproval;
import com.acme.hrms.leave.LeaveDtos.TeamCalendarEntry;
import com.acme.hrms.leave.LeaveRequestRepository.NewRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 on PostgreSQL (Testcontainers, frozen seed): the STORED {@code available} column, {@code
 * uk_leave_bal}, the V4 idempotency index, balance no-ops without a row (QUIRK-02), overlap incl.
 * the BUG-06 exception, pending-approval / team-calendar projections and V4 applying on the P0
 * baseline.
 */
class LeaveRepositoryTest {

  private static JdbcTemplate jdbc;
  private static LeaveRequestRepository requests;
  private static LeaveBalanceRepository balances;
  private static LeaveTypeRepository types;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    requests = new LeaveRequestRepository(jdbc);
    balances = new LeaveBalanceRepository(jdbc);
    types = new LeaveTypeRepository(jdbc);
  }

  @Test
  void flywayV4AppliedOnBaseline() {
    Integer v =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '4' and success",
            Integer.class);
    assertThat(v).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name ="
                    + " 'leave_accrual_log' and column_name = 'accrual_type'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "select authority from role_permissions where role_id = 3 and authority like"
                    + " 'LEAVE:%' order by 1",
                String.class))
        .contains("LEAVE:ADMIN", "LEAVE:APPROVE", "LEAVE:VIEW_ALL");
  }

  @Test
  void availableIsStoredGeneratedAndRecomputed() {
    // seed 9001: opening 5 + accrued 7.5 - used 3 + adj 0 - pending 0 = 9.5
    assertThat(balances.available(2, 1, 2024)).isEqualByComparingTo("9.5");
    assertThat(
            jdbc.queryForObject(
                "select is_generated from information_schema.columns where table_name ="
                    + " 'leave_balances' and column_name = 'available'",
                String.class))
        .isEqualTo("ALWAYS");

    balances.addPending(2, 1, 2024, new BigDecimal("2"), "t");
    assertThat(balances.available(2, 1, 2024)).isEqualByComparingTo("7.5");
    balances.pendingToUsed(2, 1, 2024, new BigDecimal("2"), "t");
    LeaveBalance b = balances.find(2, 1, 2024).orElseThrow();
    assertThat(b.pending()).isEqualByComparingTo("0");
    assertThat(b.used()).isEqualByComparingTo("5");
    assertThat(b.available()).isEqualByComparingTo("7.5");
    balances.addUsed(2, 1, 2024, new BigDecimal("-2"), "t");
    assertThat(balances.available(2, 1, 2024)).isEqualByComparingTo("9.5");

    assertThatThrownBy(
            () -> jdbc.update("update leave_balances set available = 1 where balance_id = 9001"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("generated");
  }

  @Test
  void ukLeaveBalRejectsDuplicateEmpTypeYear() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into leave_balances (balance_id, emp_id, leave_type_id, calendar_year,"
                        + " created_by) values (nextval('seq_leave_balance'), 2, 1, 2024, 't')"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_leave_bal");
  }

  @Test
  void missingBalanceRowIsZeroAndMutationsAreNoOps() {
    assertThat(balances.available(2, 1, 2031)).isEqualByComparingTo("0");
    assertThat(balances.addPending(2, 1, 2031, BigDecimal.ONE, "t")).isZero();
    assertThat(balances.addUsed(2, 1, 2031, BigDecimal.ONE, "t")).isZero();
    assertThat(balances.pendingToUsed(2, 1, 2031, BigDecimal.ONE, "t")).isZero();
    assertThat(balances.find(2, 1, 2031)).isEmpty();
  }

  @Test
  void balancesListedByLeaveTypeNameWithStoredAvailable() {
    List<LeaveBalance> list = balances.listForEmployee(12, 2024);
    assertThat(list).extracting(LeaveBalance::leaveTypeCode).containsExactly("PTO", "SICK");
    assertThat(list.get(0).available()).isEqualByComparingTo("6.5"); // 0+7.5-2+1-0
    assertThat(balances.listForEmployee(12, 2023)).isEmpty();
  }

  @Test
  void leaveTypesActiveOnly() {
    assertThat(types.findActive(5).orElseThrow().requiresApproval()).isFalse();
    assertThat(types.findActive(3).orElseThrow().minTenureDays()).isEqualTo(90);
    assertThat(types.findActive(999)).isEmpty();
    jdbc.update("update leave_types set active_flag = 'N' where leave_type_id = 6");
    assertThat(types.findActive(6)).isEmpty();
  }

  @Test
  void overlapIgnoresRejectedCancelledAndAllowsAmPmPair() {
    // seed 1001: emp 22 PENDING 2024-07-08..12 ; 1004: emp 33 REJECTED 05-13..14
    assertThat(
            requests.overlaps(
                22, LocalDate.of(2024, 7, 12), LocalDate.of(2024, 7, 15), false, null))
        .isTrue();
    assertThat(
            requests.overlaps(
                22, LocalDate.of(2024, 7, 15), LocalDate.of(2024, 7, 16), false, null))
        .isFalse();
    assertThat(
            requests.overlaps(
                33, LocalDate.of(2024, 5, 13), LocalDate.of(2024, 5, 13), false, null))
        .isFalse();

    long am =
        requests.insert(
            new NewRequest(
                11,
                1,
                LocalDate.of(2031, 3, 3),
                LocalDate.of(2031, 3, 3),
                new BigDecimal("0.5"),
                true,
                "AM",
                "PENDING",
                null,
                10L,
                null,
                "t"));
    assertThat(requests.get(am).halfDayPeriod()).isEqualTo("AM");
    // BUG-06: PM on the same date is not an overlap ...
    assertThat(
            requests.overlaps(11, LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 3), true, "PM"))
        .isFalse();
    // ... but another AM, a full day, or a range across the date is
    assertThat(
            requests.overlaps(11, LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 3), true, "AM"))
        .isTrue();
    assertThat(
            requests.overlaps(11, LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 3), false, null))
        .isTrue();
    assertThat(
            requests.overlaps(11, LocalDate.of(2031, 3, 2), LocalDate.of(2031, 3, 4), true, "PM"))
        .isTrue();
    requests.cancel(am, "x", "t");
    assertThat(
            requests.overlaps(11, LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 3), false, null))
        .isFalse();
    LeaveRequest cancelled = requests.get(am);
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.reason()).isEqualTo("x");
  }

  @Test
  void decideOnlyFromPendingAndSetsActualApprover() {
    long id =
        requests.insert(
            new NewRequest(
                12,
                1,
                LocalDate.of(2031, 4, 7),
                LocalDate.of(2031, 4, 8),
                new BigDecimal("2"),
                false,
                null,
                "PENDING",
                "r",
                10L,
                null,
                "t"));
    assertThat(requests.decide(id, "APPROVED", 1L, "ok", "1")).isEqualTo(1);
    LeaveRequest r = requests.get(id);
    assertThat(r.status()).isEqualTo("APPROVED");
    assertThat(r.approverEmpId()).isEqualTo(1L);
    assertThat(r.approverName()).isEqualTo("JAMES RICHARDSON");
    assertThat(r.approvalComments()).isEqualTo("ok");
    assertThat(r.approvalDate()).isNotNull();
    assertThat(requests.decide(id, "REJECTED", 1L, "no", "1")).isZero();
    assertThat(requests.cancel(id, "c", "t")).isEqualTo(1);
    assertThat(requests.cancel(id, "c", "t")).isZero();
  }

  @Test
  void listCountPendingAndTeamCalendarProjections() {
    assertThat(requests.countForEmployee(22, List.of(), null)).isEqualTo(1);
    assertThat(requests.countForEmployee(22, List.of("PENDING", "APPROVED"), 2024)).isEqualTo(1);
    assertThat(requests.countForEmployee(22, List.of("REJECTED"), 2024)).isZero();
    assertThat(requests.countForEmployee(22, List.of(), 2023)).isZero();
    assertThat(requests.listForEmployee(22, List.of(), null, 0, 10))
        .extracting(LeaveRequest::requestId)
        .containsExactly(1001L);
    assertThat(requests.listForEmployee(22, List.of(), null, 1, 10)).isEmpty();
    LeaveRequest r = requests.get(1001);
    assertThat(r.empName()).isEqualTo("THOMAS BAKER");
    assertThat(r.leaveTypeCode()).isEqualTo("PTO");
    assertThat(r.approverName()).isEqualTo("JENNIFER PARK");
    assertThat(requests.find(424242L)).isEmpty();

    List<PendingLeaveApproval> pending = requests.pendingFor(21);
    assertThat(pending).extracting(PendingLeaveApproval::requestId).containsExactly(1001L, 1003L);
    assertThat(pending.get(0).empNumber()).isEqualTo("EMP-000022");
    assertThat(pending.get(1).halfDay()).isTrue();
    assertThat(pending.get(1).halfDayPeriod()).isEqualTo("PM");

    // only APPROVED/TAKEN of direct reports: 1001/1003 are PENDING, 1002 (emp 12 -> mgr 10) is
    // APPROVED
    assertThat(requests.teamCalendar(21, LocalDate.of(2024, 7, 1), LocalDate.of(2024, 8, 31)))
        .isEmpty();
    List<TeamCalendarEntry> cal =
        requests.teamCalendar(10, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30));
    assertThat(cal).extracting(TeamCalendarEntry::requestId).containsExactly(1002L);
    assertThat(cal.get(0).status()).isEqualTo("APPROVED");
    assertThat(cal.get(0).halfDay()).isFalse();
    assertThat(requests.teamCalendar(10, LocalDate.of(2024, 6, 4), LocalDate.of(2024, 6, 30)))
        .isEmpty();
    assertThat(requests.teamCalendar(2, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
        .isEmpty();
  }

  @Test
  void oracleNumberRendering() {
    assertThat(OracleNumber.render(new BigDecimal("9.50"))).isEqualTo("9.5");
    assertThat(OracleNumber.render(new BigDecimal("3.00"))).isEqualTo("3");
    assertThat(OracleNumber.render(new BigDecimal("0.5"))).isEqualTo(".5");
    assertThat(OracleNumber.render(new BigDecimal("0.00"))).isEqualTo("0");
    assertThat(OracleNumber.render(new BigDecimal("-0.5"))).isEqualTo("-.5");
    assertThat(OracleNumber.render(new BigDecimal("100"))).isEqualTo("100");
  }
}
