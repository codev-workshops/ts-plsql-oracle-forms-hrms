package com.acme.hrms.reporting;

import com.acme.hrms.reporting.ReportingDtos.EmployeeCompensationPage;
import com.acme.hrms.reporting.ReportingDtos.EmployeeCompensationRow;
import com.acme.hrms.reporting.ReportingDtos.EmployeeDirectoryPage;
import com.acme.hrms.reporting.ReportingDtos.EmployeeDirectoryRow;
import com.acme.hrms.reporting.ReportingDtos.LeaveSummaryPage;
import com.acme.hrms.reporting.ReportingDtos.LeaveSummaryRow;
import com.acme.hrms.reporting.ReportingDtos.OrgHierarchyPage;
import com.acme.hrms.reporting.ReportingDtos.OrgHierarchyRow;
import com.acme.hrms.reporting.ReportingDtos.PayrollLatestPage;
import com.acme.hrms.reporting.ReportingDtos.PayrollLatestRow;
import com.acme.hrms.reporting.ReportingDtos.PendingApprovalPage;
import com.acme.hrms.reporting.ReportingDtos.PendingApprovalRow;
import com.acme.hrms.validation.dto.reporting.EmployeeCompensationQuery;
import com.acme.hrms.validation.dto.reporting.EmployeeDirectoryQuery;
import com.acme.hrms.validation.dto.reporting.LeaveSummaryQuery;
import com.acme.hrms.validation.dto.reporting.OrgHierarchyQuery;
import com.acme.hrms.validation.dto.reporting.PayrollLatestQuery;
import com.acme.hrms.validation.dto.reporting.PendingApprovalsQuery;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/reports/*} of contracts/p5-reporting-decommission/openapi.yaml. Each report has the
 * negotiated route ({@code Accept: application/json | text/csv}, else 406) and the {@code .csv}
 * alias. Query beans are the hrms-validation DTOs, bound from the query string and validated before
 * the service runs.
 */
@RestController
public class ReportingController {

  private static final Set<String> NONE = Set.of();

  private final ReportingService service;

  public ReportingController(ReportingService service) {
    this.service = service;
  }

  // ---------------------------------------------------------------- employee directory
  @GetMapping("/api/reports/employee-directory")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> employeeDirectory(
      @Valid @ModelAttribute EmployeeDirectoryQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return employeeDirectory(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/employee-directory.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> employeeDirectoryCsv(@Valid @ModelAttribute EmployeeDirectoryQuery q) {
    return employeeDirectory(q, true);
  }

  private ResponseEntity<?> employeeDirectory(EmployeeDirectoryQuery q, boolean csv) {
    EmployeeDirectoryPage page = service.employeeDirectory(q, csv);
    return csv
        ? CsvWriter.respond(
            "employee-directory", page.asOf(), EmployeeDirectoryRow.class, page.content(), NONE)
        : json(page);
  }

  // ---------------------------------------------------------------- org hierarchy
  @GetMapping("/api/reports/org-hierarchy")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> orgHierarchy(
      @Valid @ModelAttribute OrgHierarchyQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return orgHierarchy(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/org-hierarchy.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> orgHierarchyCsv(@Valid @ModelAttribute OrgHierarchyQuery q) {
    return orgHierarchy(q, true);
  }

  private ResponseEntity<?> orgHierarchy(OrgHierarchyQuery q, boolean csv) {
    OrgHierarchyPage page = service.orgHierarchy(q, csv);
    return csv
        ? CsvWriter.respond(
            "org-hierarchy", page.asOf(), OrgHierarchyRow.class, page.content(), NONE)
        : json(page);
  }

  // ---------------------------------------------------------------- employee compensation
  @GetMapping("/api/reports/employee-compensation")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
  public ResponseEntity<?> employeeCompensation(
      @Valid @ModelAttribute EmployeeCompensationQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return employeeCompensation(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/employee-compensation.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
  public ResponseEntity<?> employeeCompensationCsv(
      @Valid @ModelAttribute EmployeeCompensationQuery q) {
    return employeeCompensation(q, true);
  }

  private ResponseEntity<?> employeeCompensation(EmployeeCompensationQuery q, boolean csv) {
    EmployeeCompensationPage page = service.employeeCompensation(q, csv);
    return csv
        ? CsvWriter.respond(
            "employee-compensation",
            page.asOf(),
            EmployeeCompensationRow.class,
            page.content(),
            NONE)
        : json(page);
  }

  // ---------------------------------------------------------------- leave summary
  @GetMapping("/api/reports/leave-summary")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> leaveSummary(
      @Valid @ModelAttribute LeaveSummaryQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return leaveSummary(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/leave-summary.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW')")
  public ResponseEntity<?> leaveSummaryCsv(@Valid @ModelAttribute LeaveSummaryQuery q) {
    return leaveSummary(q, true);
  }

  private ResponseEntity<?> leaveSummary(LeaveSummaryQuery q, boolean csv) {
    LeaveSummaryPage page = service.leaveSummary(q, csv);
    return csv
        ? CsvWriter.respond(
            "leave-summary",
            page.asOf(),
            LeaveSummaryRow.class,
            page.content(),
            Set.of("legacyAvailable"))
        : json(page);
  }

  // ---------------------------------------------------------------- payroll latest
  @GetMapping("/api/reports/payroll-latest")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
  public ResponseEntity<?> payrollLatest(
      @Valid @ModelAttribute PayrollLatestQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return payrollLatest(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/payroll-latest.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
  public ResponseEntity<?> payrollLatestCsv(@Valid @ModelAttribute PayrollLatestQuery q) {
    return payrollLatest(q, true);
  }

  private ResponseEntity<?> payrollLatest(PayrollLatestQuery q, boolean csv) {
    PayrollLatestPage page = service.payrollLatest(q, csv);
    return csv
        ? CsvWriter.respond(
            "payroll-latest", service.asOf(null), PayrollLatestRow.class, page.content(), NONE)
        : json(page);
  }

  // ---------------------------------------------------------------- pending approvals
  @GetMapping("/api/reports/pending-approvals")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') or #q.mine == true")
  public ResponseEntity<?> pendingApprovals(
      @Valid @ModelAttribute PendingApprovalsQuery q,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
    return pendingApprovals(q, CsvWriter.wantsCsv(accept, false));
  }

  @GetMapping("/api/reports/pending-approvals.csv")
  @PreAuthorize("hasAuthority('REPORTS:VIEW') or #q.mine == true")
  public ResponseEntity<?> pendingApprovalsCsv(@Valid @ModelAttribute PendingApprovalsQuery q) {
    return pendingApprovals(q, true);
  }

  private ResponseEntity<?> pendingApprovals(PendingApprovalsQuery q, boolean csv) {
    PendingApprovalPage page = service.pendingApprovals(q, csv);
    return csv
        ? CsvWriter.respond(
            "pending-approvals", page.asOf(), PendingApprovalRow.class, page.content(), NONE)
        : json(page);
  }

  private static ResponseEntity<?> json(Object body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
