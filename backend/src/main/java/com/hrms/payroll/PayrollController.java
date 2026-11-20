package com.hrms.payroll;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.payroll.PayrollModels.CreatePayrollRunRequest;
import com.hrms.payroll.PayrollModels.GeneratePayPeriodsRequest;
import com.hrms.payroll.PayrollModels.PayFrequency;
import com.hrms.payroll.PayrollModels.PayPeriod;
import com.hrms.payroll.PayrollModels.PayPeriodGeneration;
import com.hrms.payroll.PayrollModels.PayPeriodStatus;
import com.hrms.payroll.PayrollModels.PayrollRun;
import com.hrms.payroll.PayrollModels.PayrollRunError;
import com.hrms.payroll.PayrollModels.PayrollRunStatus;
import com.hrms.payroll.PayrollModels.Payslip;
import com.hrms.payroll.PayrollModels.ReversePayrollRequest;
import com.hrms.payroll.PayrollModels.SalaryRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payroll API (contract amendment v1.2-P6). Every route needs PAYROLL:VIEW (FRM-060). Commands only — no generic
 * CRUD on periods, runs or salary records (FRM-039, FRM-065). Create, calculate, generate and close need
 * PAYROLL:EDIT (OQ-09, FRM-064); approve and reverse need PAYROLL:APPROVE (FRM-063).
 */
@RestController
@RequestMapping("/api/v1/payroll")
@PreAuthorize("hasAuthority('PAYROLL:VIEW')")
public class PayrollController {

    private final PayPeriodService periods;
    private final PayrollRunService runs;
    private final PayrollSalaryRecords salaries;

    public PayrollController(PayPeriodService periods, PayrollRunService runs, PayrollSalaryRecords salaries) {
        this.periods = periods;
        this.runs = runs;
        this.salaries = salaries;
    }

    @GetMapping("/pay-periods")
    List<PayPeriod> periods(@RequestParam(required = false) PayPeriodStatus status,
            @RequestParam(required = false) PayFrequency frequency,
            @RequestParam(required = false) @Min(1900) @Max(9999) Integer year) {
        return periods.list(status, frequency, year);
    }

    @GetMapping("/pay-periods/{periodId}")
    PayPeriod period(@PathVariable long periodId) {
        return periods.get(periodId);
    }

    @PostMapping("/pay-periods/generate")
    @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
    ResponseEntity<PayPeriodGeneration> generate(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody GeneratePayPeriodsRequest body, HttpServletRequest http) {
        PayPeriodGeneration g = periods.generate(body.year(), body.frequency(), PayrollCaller.of(p, http.getRemoteAddr()));
        return ResponseEntity.status(g.created().isEmpty() ? HttpStatus.OK : HttpStatus.CREATED).body(g);
    }

    @PostMapping("/pay-periods/{periodId}/close")
    @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
    PayPeriod close(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long periodId, HttpServletRequest http) {
        return periods.close(periodId, PayrollCaller.of(p, http.getRemoteAddr()));
    }

    @GetMapping("/runs")
    List<PayrollRun> runs(@RequestParam(required = false) Long periodId,
            @RequestParam(required = false) PayrollRunStatus status) {
        return runs.list(periodId, status);
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
    ResponseEntity<PayrollRun> create(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody CreatePayrollRunRequest body, HttpServletRequest http) {
        PayrollRun run = runs.create(PayrollCaller.of(p, http.getRemoteAddr()), body.periodId(), body.runType());
        return ResponseEntity.created(URI.create("/api/v1/payroll/runs/" + run.runId())).body(run);
    }

    @GetMapping("/runs/{runId}")
    PayrollRun run(@PathVariable long runId) {
        return runs.get(runId);
    }

    @PostMapping("/runs/{runId}/calculate")
    @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
    PayrollRun calculate(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long runId, HttpServletRequest http) {
        return runs.calculate(PayrollCaller.of(p, http.getRemoteAddr()), runId);
    }

    @PostMapping("/runs/{runId}/approve")
    @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
    PayrollRun approve(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long runId, HttpServletRequest http) {
        return runs.approve(PayrollCaller.of(p, http.getRemoteAddr()), runId);
    }

    @PostMapping("/runs/{runId}/reverse")
    @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
    PayrollRun reverse(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long runId,
            @Valid @RequestBody ReversePayrollRequest body, HttpServletRequest http) {
        return runs.reverse(PayrollCaller.of(p, http.getRemoteAddr()), runId, body.reason());
    }

    @GetMapping("/runs/{runId}/payslips")
    List<Payslip> payslips(@PathVariable long runId, @RequestParam(required = false) Long empId) {
        return runs.payslips(runId, empId);
    }

    @GetMapping("/runs/{runId}/errors")
    List<PayrollRunError> errors(@PathVariable long runId) {
        return runs.errors(runId);
    }

    @GetMapping("/employees/{empId}/salary-records")
    List<SalaryRecord> salaryRecords(@PathVariable long empId) {
        return salaries.salaryRecords(empId);
    }
}
