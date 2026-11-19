package com.hrms.payroll;

import static com.hrms.payroll.PayrollModels.*;

import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.salary.SalaryRecord;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payroll")
class PayrollController {
    private final PayrollService payroll;
    private final PayrollExports exports;
    private final Clock clock;

    PayrollController(PayrollService payroll, PayrollExports exports, Clock clock) {
        this.payroll = payroll;
        this.exports = exports;
        this.clock = clock;
    }

    @GetMapping("/periods")
    List<PayPeriod> periods(@RequestParam(required = false) Integer year,
                            @RequestParam(required = false) PeriodFilter status, CurrentUser user) {
        return payroll.periods(year, status, user);
    }

    @PostMapping("/periods")
    @ResponseStatus(HttpStatus.CREATED)
    List<PayPeriod> createPeriods(@Valid @RequestBody CreatePeriods request, CurrentUser user) {
        return payroll.createPeriods(request, user);
    }

    @GetMapping("/periods/current")
    PayPeriod currentPeriod(CurrentUser user) {
        return payroll.currentPeriod(user);
    }

    @PostMapping("/periods/{periodId}/close")
    PayPeriod closePeriod(@PathVariable long periodId, CurrentUser user) {
        return payroll.closePeriod(periodId, user);
    }

    @GetMapping("/runs")
    List<PayrollRun> runs(@RequestParam(required = false) Long periodId, CurrentUser user) {
        return payroll.runs(periodId, user);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    PayrollRun createRun(@Valid @RequestBody CreateRun request, CurrentUser user) {
        return payroll.createRun(request, user);
    }

    @GetMapping("/runs/{runId}")
    PayrollRun run(@PathVariable long runId, CurrentUser user) {
        return payroll.getRun(runId, user);
    }

    @PostMapping("/runs/{runId}/calculate")
    PayrollRun calculateRun(@PathVariable long runId, CurrentUser user) {
        return payroll.calculateRun(runId, user);
    }

    @PostMapping("/runs/{runId}/approve")
    PayrollRun approveRun(@PathVariable long runId, CurrentUser user) {
        return payroll.approveRun(runId, user);
    }

    @PostMapping("/runs/{runId}/reverse")
    PayrollRun reverseRun(@PathVariable long runId, CurrentUser user) {
        return payroll.reverseRun(runId, user);
    }

    @GetMapping("/runs/{runId}/details")
    List<PayrollDetail> details(@PathVariable long runId, @RequestParam(required = false) Long empId,
                                CurrentUser user) {
        return payroll.details(runId, empId, user);
    }

    @GetMapping("/runs/{runId}/register.csv")
    ResponseEntity<String> register(@PathVariable long runId, CurrentUser user) {
        return attachment(exports.register(runId, user), "text/csv", "PAY_REGISTER_" + runId + ".csv");
    }

    @GetMapping("/runs/{runId}/gl-journal")
    ResponseEntity<String> glJournal(@PathVariable long runId, CurrentUser user) {
        return attachment(exports.glJournal(runId, user), "text/plain", "GL_JOURNAL_" + runId + "_" + today() + ".dat");
    }

    @GetMapping("/exports/benefits")
    ResponseEntity<String> benefits(CurrentUser user) {
        return attachment(exports.benefits(user), "text/plain", "BENEFITS_" + today() + ".txt");
    }

    @GetMapping("/payslips/{runId}/{empId}")
    Payslip payslip(@PathVariable long runId, @PathVariable long empId, CurrentUser user) {
        return payroll.payslip(runId, empId, user);
    }

    @GetMapping("/employees/{empId}/ytd")
    Ytd ytd(@PathVariable long empId, @RequestParam(required = false) Integer year, CurrentUser user) {
        return payroll.ytd(empId, year, user);
    }

    @PostMapping("/employees/{empId}/salary")
    @ResponseStatus(HttpStatus.CREATED)
    SalaryRecord changeSalary(@PathVariable long empId, @Valid @RequestBody ChangeSalary request, CurrentUser user) {
        return payroll.changeSalary(empId, request, user);
    }

    @GetMapping("/employees/{empId}/pay-elements")
    List<EmployeeElement> payElements(@PathVariable long empId, CurrentUser user) {
        return payroll.payElements(empId, user);
    }

    @GetMapping("/employees/{empId}/tax-info")
    TaxInfo taxInfo(@PathVariable long empId, @RequestParam(required = false) Integer year, CurrentUser user) {
        return payroll.taxInfo(empId, year, user);
    }

    @GetMapping("/tax-brackets")
    List<TaxBracket> taxBrackets(@RequestParam int year, @RequestParam(required = false) FilingStatus filingStatus,
                                 CurrentUser user) {
        return payroll.taxBrackets(year, filingStatus, user);
    }

    private String today() {
        return LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static ResponseEntity<String> attachment(String content, String mediaType, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.parseMediaType(mediaType), StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
