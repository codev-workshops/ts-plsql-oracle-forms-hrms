package com.hrms.reporting;

import com.hrms.auth.Action;
import com.hrms.auth.HrmsPrincipal;
import com.hrms.auth.Module;
import com.hrms.reporting.ReportModels.EmployeeCompensation;
import com.hrms.reporting.ReportModels.LeaveSummaryRow;
import com.hrms.reporting.ReportModels.OrgNode;
import com.hrms.reporting.ReportModels.PayrollLatest;
import com.hrms.reporting.ReportModels.PendingApproval;
import com.hrms.reporting.ReportModels.ReportDescriptor;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports & Analytics (the dead HRMS_REPORTS target, OQ-03): read-only endpoints for the legacy views,
 * behind REPORTS:VIEW (FRM-012). Compensation and payroll figures also need PAYROLL:VIEW (VW-003).
 * The other PKG_REPORTING reports and the pay register are deferred (OQ-03).
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAuthority('REPORTS:VIEW')")
public class ReportController {

    private static final String BASE = "/api/v1/reports";

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    List<ReportDescriptor> catalogue(@AuthenticationPrincipal HrmsPrincipal p) {
        boolean pay = p.has(Module.PAYROLL, Action.VIEW);
        return List.of(
                new ReportDescriptor("PENDING_APPROVALS", "Pending Approvals", BASE + "/pending-approvals",
                        "VW_PENDING_APPROVALS", "REPORTS:VIEW", true),
                new ReportDescriptor("LEAVE_SUMMARY", "Leave Summary", BASE + "/leave-summary",
                        "VW_LEAVE_SUMMARY", "REPORTS:VIEW", true),
                new ReportDescriptor("ORG_HIERARCHY", "Organization Hierarchy", BASE + "/org-hierarchy",
                        "VW_ORG_HIERARCHY", "REPORTS:VIEW", true),
                new ReportDescriptor("EMPLOYEE_COMPENSATION", "Employee Compensation",
                        BASE + "/employee-compensation", "VW_EMPLOYEE_COMPENSATION", "REPORTS:VIEW+PAYROLL:VIEW", pay),
                new ReportDescriptor("PAYROLL_LATEST", "Latest Approved Payroll", BASE + "/payroll-latest",
                        "VW_PAYROLL_LATEST", "REPORTS:VIEW+PAYROLL:VIEW", pay));
    }

    @GetMapping("/pending-approvals")
    List<PendingApproval> pendingApprovals(@RequestParam(required = false) Long approverId,
            @RequestParam(required = false) ApprovalType approvalType) {
        return reports.pendingApprovals(approverId, approvalType == null ? null : approvalType.name());
    }

    @GetMapping("/leave-summary")
    List<LeaveSummaryRow> leaveSummary(@RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long empId, @RequestParam(required = false) Long deptId) {
        return reports.leaveSummary(year, empId, deptId);
    }

    @GetMapping("/org-hierarchy")
    List<OrgNode> orgHierarchy(@RequestParam(required = false) Long rootEmpId,
            @RequestParam(required = false) Integer maxDepth) {
        return reports.orgHierarchy(rootEmpId, maxDepth);
    }

    @GetMapping("/employee-compensation")
    @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
    List<EmployeeCompensation> employeeCompensation() {
        return reports.employeeCompensation();
    }

    @GetMapping("/payroll-latest")
    @PreAuthorize("hasAuthority('REPORTS:VIEW') and hasAuthority('PAYROLL:VIEW')")
    List<PayrollLatest> payrollLatest() {
        return reports.payrollLatest();
    }

    public enum ApprovalType { LEAVE, PERFORMANCE }
}
