package com.acme.hrms.payroll.register;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.employee.EmployeeBankAccountProjection;
import com.acme.hrms.employee.EmployeeBankAccountProjection.MaskedBankAccount;
import com.acme.hrms.payroll.PayrollDtos;
import com.acme.hrms.payroll.run.PayrollRunRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_PAYROLL.generate_payroll_register as a streamed RFC 4180 CSV (the legacy UTL_FILE target
 * never existed). One row per employee with at least one CALCULATED row; ERROR-only employees are
 * excluded; masked bank columns only on request and never the full numbers.
 */
@Service
public class PayRegisterExporter {

  public static final String HEADER =
      "EMP_NUMBER,EMPLOYEE_NAME,DEPARTMENT,GROSS_PAY,FED_TAX,STATE_TAX,SS_TAX,MEDICARE,DEDUCTIONS,NET_PAY";
  public static final String BANK_HEADER = ",BANK_NAME,ROUTING_LAST4,ACCOUNT_LAST4";
  private static final String CRLF = "\r\n";
  private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private final JdbcTemplate jdbc;
  private final PayrollRunRepository runs;
  private final EmployeeBankAccountProjection bankAccounts;
  private final AuditService audit;
  private final Clock clock;

  public PayRegisterExporter(
      JdbcTemplate jdbc,
      PayrollRunRepository runs,
      EmployeeBankAccountProjection bankAccounts,
      AuditService audit,
      Clock clock) {
    this.jdbc = jdbc;
    this.runs = runs;
    this.bankAccounts = bankAccounts;
    this.audit = audit;
    this.clock = clock;
  }

  public record RegisterRow(
      long empId,
      String empNumber,
      String employeeName,
      @Nullable String department,
      BigDecimal gross,
      BigDecimal fedTax,
      BigDecimal stateTax,
      BigDecimal ssTax,
      BigDecimal medicare,
      BigDecimal deductions,
      BigDecimal netPay) {}

  public String fileName(long runId) {
    return "PAY_REGISTER_" + runId + "_" + LocalDateTime.now(clock).format(FILE_TS) + ".csv";
  }

  /** Verifies the run exists and records the download before any byte is streamed. */
  @Transactional
  public void authorizeDownload(long runId, boolean includeBank, CallerIdentity caller) {
    runs.core(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    audit.log(
        "PAYROLL_RUNS",
        runId,
        AuditService.Action.REGISTER_DOWNLOAD,
        null,
        "includeBank=" + includeBank,
        caller.userId(),
        null,
        null);
  }

  @Transactional(readOnly = true)
  public List<RegisterRow> rows(long runId) {
    return jdbc.query(
        "select e.emp_id, e.emp_number, e.first_name, e.last_name, d.dept_name,"
            + " coalesce(sum(case when pd.element_type = 'EARNING' then pd.amount end), 0) gross,"
            + " coalesce(sum(case when pd.element_id = 100 then -pd.amount end), 0) fed_tax,"
            + " coalesce(sum(case when pd.element_id = 101 then -pd.amount end), 0) state_tax,"
            + " coalesce(sum(case when pd.element_id = 102 then -pd.amount end), 0) ss_tax,"
            + " coalesce(sum(case when pd.element_id = 103 then -pd.amount end), 0) medicare,"
            + " coalesce(sum(case when pd.element_type in ('DEDUCTION', 'BENEFIT')"
            + "   then -pd.amount end), 0) deductions,"
            + " coalesce(sum(pd.amount), 0) net_pay"
            + " from payroll_details pd"
            + " join employees e on e.emp_id = pd.emp_id"
            + " left join departments d on d.dept_id = e.dept_id"
            + " where pd.run_id = ? and pd.status = 'CALCULATED'"
            + " group by e.emp_id, e.emp_number, e.first_name, e.last_name, d.dept_name"
            + " order by e.last_name, e.first_name, e.emp_number",
        (rs, i) ->
            new RegisterRow(
                rs.getLong("emp_id"),
                rs.getString("emp_number"),
                rs.getString("first_name") + " " + rs.getString("last_name"),
                rs.getString("dept_name"),
                rs.getBigDecimal("gross"),
                rs.getBigDecimal("fed_tax"),
                rs.getBigDecimal("state_tax"),
                rs.getBigDecimal("ss_tax"),
                rs.getBigDecimal("medicare"),
                rs.getBigDecimal("deductions"),
                rs.getBigDecimal("net_pay")),
        runId);
  }

  public void write(long runId, boolean includeBank, OutputStream out) {
    try {
      out.write(
          (HEADER + (includeBank ? BANK_HEADER : "") + CRLF).getBytes(StandardCharsets.UTF_8));
      for (RegisterRow row : rows(runId)) {
        StringBuilder line = new StringBuilder();
        line.append(field(row.empNumber()))
            .append(',')
            .append(field(row.employeeName()))
            .append(',')
            .append(field(row.department()))
            .append(',')
            .append(PayrollDtos.money(row.gross()))
            .append(',')
            .append(PayrollDtos.money(row.fedTax()))
            .append(',')
            .append(PayrollDtos.money(row.stateTax()))
            .append(',')
            .append(PayrollDtos.money(row.ssTax()))
            .append(',')
            .append(PayrollDtos.money(row.medicare()))
            .append(',')
            .append(PayrollDtos.money(row.deductions()))
            .append(',')
            .append(PayrollDtos.money(row.netPay()));
        if (includeBank) {
          Optional<MaskedBankAccount> bank = bankAccounts.primary(row.empId());
          line.append(',')
              .append(field(bank.map(MaskedBankAccount::bankName).orElse(null)))
              .append(',')
              .append(field(bank.map(MaskedBankAccount::routingLast4).orElse("")))
              .append(',')
              .append(field(bank.map(MaskedBankAccount::accountLast4).orElse("")));
        }
        line.append(CRLF);
        out.write(line.toString().getBytes(StandardCharsets.UTF_8));
      }
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** RFC 4180 field: quoted when it contains a comma, quote or line break; quotes doubled. */
  static String field(@Nullable String value) {
    if (value == null) {
      return "";
    }
    boolean quote =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
  }
}
