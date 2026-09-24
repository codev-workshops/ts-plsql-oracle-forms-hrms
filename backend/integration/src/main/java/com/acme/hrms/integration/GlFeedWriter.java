package com.acme.hrms.integration;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.integration.IntegrationDtos.Artefact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code PKG_INTEGRATION.generate_gl_journal} on PostgreSQL: pipe-delimited {@code H}/{@code
 * D}/{@code T} records, one {@code D} per {@code cost_center x gl_account} (EARNING = debit,
 * everything else = credit), {@code ERROR} details excluded.
 */
@Component
public class GlFeedWriter {
  static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  static final String LF = "\n";

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public GlFeedWriter(NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  record Run(long runId, String status, LocalDate payDate, String periodName) {}

  record Line(String costCenter, String glAccount, String elementType, BigDecimal amount) {}

  public Artefact write(long runId) {
    List<Run> runs =
        jdbc.query(
            """
            select r.run_id, r.status, p.pay_date, p.period_name
              from payroll_runs r join pay_periods p on p.period_id = r.period_id
             where r.run_id = :id
            """,
            Map.of("id", runId),
            (rs, i) ->
                new Run(
                    rs.getLong("run_id"),
                    rs.getString("status"),
                    rs.getObject("pay_date", LocalDate.class),
                    rs.getString("period_name")));
    if (runs.isEmpty()) {
      throw new HrmsException(ErrorCode.RUN_NOT_FOUND, "runId");
    }
    Run run = runs.get(0);
    if (!"APPROVED".equals(run.status()) && !"PAID".equals(run.status())) {
      throw new HrmsException(
          ErrorCode.RUN_NOT_EXPORTABLE,
          String.format(ErrorCode.RUN_NOT_EXPORTABLE.defaultMessage(), run.status()),
          "runId");
    }
    List<Line> lines =
        jdbc.query(
            """
            select coalesce(d.cost_center, 'UNASSIGNED') as cost_center,
                   pe.gl_account_code, pe.element_type, sum(pd.amount) as total
              from payroll_details pd
              join employees e on e.emp_id = pd.emp_id
              join departments d on d.dept_id = e.dept_id
              join pay_elements pe on pe.element_id = pd.element_id
             where pd.run_id = :id and pd.status <> 'ERROR' and pe.gl_account_code is not null
             group by coalesce(d.cost_center, 'UNASSIGNED'), pe.gl_account_code, pe.element_type
             order by 1, 2, 3
            """,
            Map.of("id", runId),
            (rs, i) ->
                new Line(
                    rs.getString("cost_center"),
                    rs.getString("gl_account_code"),
                    rs.getString("element_type"),
                    rs.getBigDecimal("total")));
    StringBuilder sb = new StringBuilder();
    sb.append("H|HRMS_PAYROLL|").append(run.payDate()).append('|').append(runId).append(LF);
    for (Line l : lines) {
      String amt = l.amount().abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
      boolean debit = "EARNING".equals(l.elementType());
      sb.append("D|")
          .append(l.costCenter())
          .append('|')
          .append(l.glAccount())
          .append('|')
          .append(debit ? amt : "0.00")
          .append('|')
          .append(debit ? "0.00" : amt)
          .append("|Payroll ")
          .append(run.periodName())
          .append("|RUN-")
          .append(runId)
          .append(LF);
    }
    sb.append("T|").append(lines.size()).append(LF);
    String name = "GL_JOURNAL_" + runId + "_" + LocalDate.now(clock).format(FILE_DATE) + ".txt";
    return new Artefact(
        "GL_JOURNAL",
        name,
        "SUCCESS",
        IntegrationFileRepository.utf8(sb.toString()),
        lines.size(),
        Long.toString(runId),
        "text/plain; charset=utf-8",
        null);
  }
}
