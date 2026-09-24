package com.acme.hrms.payroll;

import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.payroll.PayrollDtos.Page;
import com.acme.hrms.payroll.PayrollDtos.PayPeriod;
import com.acme.hrms.payroll.PayrollDtos.PayrollDetail;
import com.acme.hrms.payroll.PayrollDtos.PayrollRun;
import com.acme.hrms.payroll.PayrollDtos.PayrollRunApproval;
import com.acme.hrms.payroll.PayrollDtos.PayrollRunStatus;
import com.acme.hrms.payroll.PayrollDtos.Payslip;
import com.acme.hrms.payroll.PayrollDtos.ShadowDiffReport;
import com.acme.hrms.payroll.payslip.PayslipService;
import com.acme.hrms.payroll.period.PayPeriodService;
import com.acme.hrms.payroll.register.PayRegisterExporter;
import com.acme.hrms.payroll.run.PayrollRunService;
import com.acme.hrms.payroll.shadow.PayrollShadowRunner;
import com.acme.hrms.validation.dto.payroll.PayPeriodListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollDetailListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollRunCreateRequest;
import com.acme.hrms.validation.dto.payroll.PayrollRunListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollRunReverseRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** contracts/p4-payroll/openapi.yaml: periods, runs, payslip, register and shadow diff. */
@RestController
public class PayrollController {

  private static final String DEFAULT_SORT = "periodStartDate,desc";

  private final PayPeriodService periods;
  private final PayrollRunService runs;
  private final PayrollValidation validation;
  private final PayslipService payslips;
  private final PayRegisterExporter register;
  private final PayrollShadowRunner shadow;

  public PayrollController(
      PayPeriodService periods,
      PayrollRunService runs,
      PayrollValidation validation,
      PayslipService payslips,
      PayRegisterExporter register,
      PayrollShadowRunner shadow) {
    this.periods = periods;
    this.runs = runs;
    this.validation = validation;
    this.payslips = payslips;
    this.register = register;
    this.shadow = shadow;
  }

  @GetMapping("/api/payroll/periods")
  @PreAuthorize("hasAuthority('PAYROLL:VIEW')")
  public Page<PayPeriod> listPeriods(PayPeriodListQuery query) {
    validation.validate(query);
    return periods.list(
        query.getStatus(),
        query.getSort() == null ? DEFAULT_SORT : query.getSort(),
        query.getPage() == null ? 0 : query.getPage(),
        query.getSize() == null ? 20 : query.getSize());
  }

  @PostMapping("/api/payroll/periods/{periodId}/close")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public PayPeriod closePeriod(@PathVariable long periodId) {
    return periods.close(periodId, CurrentCaller.require());
  }

  @GetMapping("/api/payroll/periods/{periodId}/runs")
  @PreAuthorize("hasAuthority('PAYROLL:VIEW')")
  public List<PayrollRun> listRuns(@PathVariable long periodId, PayrollRunListQuery query) {
    validation.validate(query);
    return runs.listByPeriod(periodId, query.getStatus());
  }

  @PostMapping("/api/payroll/periods/{periodId}/runs")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public ResponseEntity<PayrollRun> createRun(
      @PathVariable long periodId,
      @RequestBody(required = false) @Nullable PayrollRunCreateRequest body) {
    PayrollRunCreateRequest req = validation.validate(body);
    PayrollRun run = runs.create(periodId, req.getRunType(), CurrentCaller.require());
    return ResponseEntity.created(URI.create("/api/payroll/runs/" + run.runId())).body(run);
  }

  @PostMapping("/api/payroll/runs/{runId}/calculate")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public ResponseEntity<PayrollRunStatus> calculate(@PathVariable long runId) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(runs.calculate(runId, CurrentCaller.require()));
  }

  @GetMapping("/api/payroll/runs/{runId}/status")
  @PreAuthorize("hasAuthority('PAYROLL:VIEW')")
  public PayrollRunStatus status(@PathVariable long runId) {
    return runs.status(runId);
  }

  @PostMapping("/api/payroll/runs/{runId}/approve")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public PayrollRunApproval approve(@PathVariable long runId) {
    return runs.approve(runId, CurrentCaller.require());
  }

  @PostMapping("/api/payroll/runs/{runId}/reverse")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public PayrollRun reverse(
      @PathVariable long runId,
      @RequestBody(required = false) @Nullable PayrollRunReverseRequest body) {
    PayrollRunReverseRequest req = validation.validate(body);
    return runs.reverse(runId, req.getReason(), CurrentCaller.require());
  }

  @GetMapping("/api/payroll/runs/{runId}/details")
  @PreAuthorize("hasAuthority('PAYROLL:VIEW')")
  public Page<PayrollDetail> details(@PathVariable long runId, PayrollDetailListQuery query) {
    validation.validate(query);
    return runs.details(
        runId,
        query.getEmpId() == null ? null : query.getEmpId().longValue(),
        query.getStatus(),
        query.getPage() == null ? 0 : query.getPage(),
        query.getSize() == null ? 100 : query.getSize());
  }

  @GetMapping("/api/payroll/runs/{runId}/payslips/{empId}")
  @PreAuthorize("hasAuthority('PAYROLL:VIEW') or #empId == authentication.principal.empId")
  public Payslip payslip(@PathVariable long runId, @PathVariable long empId) {
    return payslips.get(runId, empId);
  }

  @GetMapping(value = "/api/payroll/runs/{runId}/register.csv", produces = "text/csv")
  @PreAuthorize(
      "hasAuthority('PAYROLL:VIEW') and (#includeBank != true or hasAuthority('PAYROLL:APPROVE'))")
  public void registerCsv(
      @PathVariable long runId,
      @RequestParam(name = "includeBank", defaultValue = "false") boolean includeBank,
      HttpServletResponse response)
      throws IOException {
    register.authorizeDownload(runId, includeBank, CurrentCaller.require());
    ContentDisposition disposition =
        ContentDisposition.attachment().filename(register.fileName(runId)).build();
    response.setStatus(HttpStatus.OK.value());
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
    response.setContentType("text/csv; charset=UTF-8");
    register.write(runId, includeBank, response.getOutputStream());
    response.flushBuffer();
  }

  @GetMapping("/api/payroll/shadow/runs/{runId}/diff")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public ShadowDiffReport shadowDiff(@PathVariable long runId) {
    return shadow.report(runId);
  }
}
