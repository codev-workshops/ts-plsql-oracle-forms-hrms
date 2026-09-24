package com.acme.hrms.reporting;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.reporting.ReportingDtos.CompensationSummary;
import com.acme.hrms.reporting.ReportingDtos.DirectorySummary;
import com.acme.hrms.reporting.ReportingDtos.EmployeeCompensationPage;
import com.acme.hrms.reporting.ReportingDtos.EmployeeDirectoryPage;
import com.acme.hrms.reporting.ReportingDtos.HeadcountByDepartment;
import com.acme.hrms.reporting.ReportingDtos.LeaveSummary;
import com.acme.hrms.reporting.ReportingDtos.LeaveSummaryPage;
import com.acme.hrms.reporting.ReportingDtos.OrgHierarchyPage;
import com.acme.hrms.reporting.ReportingDtos.PageMeta;
import com.acme.hrms.reporting.ReportingDtos.PayrollLatestPage;
import com.acme.hrms.reporting.ReportingDtos.PayrollLatestRow;
import com.acme.hrms.reporting.ReportingDtos.PendingApprovalPage;
import com.acme.hrms.reporting.ReportingRepository.Rows;
import com.acme.hrms.validation.dto.reporting.EmployeeCompensationQuery;
import com.acme.hrms.validation.dto.reporting.EmployeeDirectoryQuery;
import com.acme.hrms.validation.dto.reporting.LeaveSummaryQuery;
import com.acme.hrms.validation.dto.reporting.OrgHierarchyQuery;
import com.acme.hrms.validation.dto.reporting.PayrollLatestQuery;
import com.acme.hrms.validation.dto.reporting.PendingApprovalsQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report families of contracts/p5-reporting-decommission/openapi.yaml: defaults ({@code asOf} =
 * today, page 0 / size 50), filter validation with the frozen legacy codes and the summary blocks
 * of the {@code PKG_REPORTING} procedures. JSON pages and CSV streams share the same row query -
 * the CSV variant passes {@code unpaged = true}.
 */
@Service
@Transactional(readOnly = true)
public class ReportingService {

  public static final int DEFAULT_SIZE = 50;

  /** Upper bound of one CSV stream; the seed is far below it. */
  static final int CSV_LIMIT = 1_000_000;

  private final ReportingRepository repo;
  private final Clock clock;

  public ReportingService(ReportingRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  LocalDate asOf(@Nullable LocalDate requested) {
    return requested == null ? LocalDate.now(clock) : requested;
  }

  private record Window(int page, int size, int limit, int offset) {}

  private static Window window(@Nullable Integer page, @Nullable Integer size, boolean unpaged) {
    if (unpaged) {
      return new Window(0, CSV_LIMIT, CSV_LIMIT, 0);
    }
    int p = page == null ? 0 : page;
    int s = size == null ? DEFAULT_SIZE : size;
    return new Window(p, s, s, p * s);
  }

  private static <T> PageMeta meta(Window w, Rows<T> rows) {
    return PageMeta.of(w.page(), w.size(), rows.total());
  }

  private void requireDepartment(@Nullable Integer deptId) {
    if (deptId != null && !repo.activeDepartment(deptId)) {
      throw new HrmsException(
          ErrorCode.INVALID_DEPARTMENT,
          String.format(ErrorCode.INVALID_DEPARTMENT.defaultMessage(), deptId),
          "deptId");
    }
  }

  @Nullable
  private static Long asLong(@Nullable Integer v) {
    return v == null ? null : v.longValue();
  }

  public EmployeeDirectoryPage employeeDirectory(EmployeeDirectoryQuery q, boolean unpaged) {
    requireDepartment(q.getDeptId());
    LocalDate asOf = asOf(q.getAsOf());
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<ReportingDtos.EmployeeDirectoryRow> rows =
        repo.employeeDirectory(
            asOf, asLong(q.getDeptId()), q.getLocationCode(), w.limit(), w.offset());
    List<HeadcountByDepartment> byDept =
        repo.headcountByDepartment(asOf, asLong(q.getDeptId()), q.getLocationCode());
    int total = byDept.stream().mapToInt(HeadcountByDepartment::headcount).sum();
    return new EmployeeDirectoryPage(
        asOf, rows.rows(), meta(w, rows), new DirectorySummary(total, byDept));
  }

  public OrgHierarchyPage orgHierarchy(OrgHierarchyQuery q, boolean unpaged) {
    if (q.getRootEmpId() != null && !repo.activeEmployee(q.getRootEmpId())) {
      throw new HrmsException(
          ErrorCode.EMPLOYEE_NOT_FOUND,
          ErrorCode.EMPLOYEE_NOT_FOUND.defaultMessage() + ": " + q.getRootEmpId(),
          "rootEmpId");
    }
    LocalDate asOf = asOf(q.getAsOf());
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<ReportingDtos.OrgHierarchyRow> rows =
        repo.orgHierarchy(asLong(q.getRootEmpId()), q.getMaxLevel(), w.limit(), w.offset());
    return new OrgHierarchyPage(asOf, rows.rows(), meta(w, rows));
  }

  public EmployeeCompensationPage employeeCompensation(
      EmployeeCompensationQuery q, boolean unpaged) {
    requireDepartment(q.getDeptId());
    LocalDate asOf = asOf(q.getAsOf());
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<ReportingDtos.EmployeeCompensationRow> rows =
        repo.employeeCompensation(
            asOf, asLong(q.getDeptId()), q.getGradeId(), w.limit(), w.offset());
    return new EmployeeCompensationPage(
        asOf,
        rows.rows(),
        meta(w, rows),
        new CompensationSummary(
            repo.compensationByDepartment(asLong(q.getDeptId()), q.getGradeId())));
  }

  public LeaveSummaryPage leaveSummary(LeaveSummaryQuery q, boolean unpaged) {
    requireDepartment(q.getDeptId());
    LocalDate asOf = asOf(q.getAsOf());
    int year = q.getYear() == null ? asOf.getYear() : q.getYear();
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<ReportingDtos.LeaveSummaryRow> rows =
        repo.leaveSummary(year, asLong(q.getDeptId()), q.getLeaveTypeId(), w.limit(), w.offset());
    return new LeaveSummaryPage(
        asOf,
        year,
        rows.rows(),
        meta(w, rows),
        new LeaveSummary(
            repo.leaveUtilizationByType(year, asLong(q.getDeptId()), q.getLeaveTypeId())));
  }

  public PayrollLatestPage payrollLatest(PayrollLatestQuery q, boolean unpaged) {
    requireDepartment(q.getDeptId());
    Long periodId;
    if (q.getPeriodId() != null) {
      periodId = q.getPeriodId().longValue();
      if (!repo.periodExists(periodId)) {
        throw new HrmsException(ErrorCode.PERIOD_NOT_FOUND, "periodId");
      }
    } else {
      periodId = repo.latestPaidPeriod();
    }
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<PayrollLatestRow> rows =
        periodId == null
            ? new Rows<>(List.of(), 0)
            : repo.payrollLatest(periodId, asLong(q.getDeptId()), w.limit(), w.offset());
    return new PayrollLatestPage(
        rows.rows(), meta(w, rows), repo.payrollSummary(periodId, asLong(q.getDeptId())));
  }

  /** {@code mine=true} scopes to the JWT caller's {@code empId}; never a request parameter. */
  public PendingApprovalPage pendingApprovals(PendingApprovalsQuery q, boolean unpaged) {
    requireDepartment(q.getDeptId());
    LocalDate asOf = asOf(q.getAsOf());
    Long approver = null;
    if (Boolean.TRUE.equals(q.getMine())) {
      CallerIdentity caller = CurrentCaller.require();
      approver = caller.empId();
    }
    Window w = window(q.getPage(), q.getSize(), unpaged);
    Rows<ReportingDtos.PendingApprovalRow> rows =
        repo.pendingApprovals(
            asOf, q.getItemType(), approver, asLong(q.getDeptId()), w.limit(), w.offset());
    return new PendingApprovalPage(
        asOf,
        rows.rows(),
        meta(w, rows),
        repo.pendingSummary(q.getItemType(), approver, asLong(q.getDeptId())));
  }
}
