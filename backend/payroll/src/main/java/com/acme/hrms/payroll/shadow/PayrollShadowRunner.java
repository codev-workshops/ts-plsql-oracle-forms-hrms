package com.acme.hrms.payroll.shadow;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.payroll.PayrollDtos;
import com.acme.hrms.payroll.PayrollDtos.ShadowDiffReport;
import com.acme.hrms.payroll.PayrollDtos.ShadowLine;
import com.acme.hrms.payroll.PayrollDtos.ShadowSummary;
import com.acme.hrms.payroll.period.PayPeriodRepository;
import com.acme.hrms.payroll.period.PayPeriodRepository.PeriodCore;
import com.acme.hrms.payroll.run.PayrollRunRepository;
import com.acme.hrms.payroll.run.PayrollRunRepository.RunCore;
import com.acme.hrms.payroll.shadow.RecordedLegacyRows.Pack;
import com.acme.hrms.payroll.tax.TaxRuleRepository;
import com.acme.hrms.validation.dto.payroll.PayrollConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CUTOVER_PLAN §8.2–§8.4: diff the Java engine's shadow rows of a run against the legacy rows for
 * the same period, per {@code (empId, elementId)} on cents. Legacy rows come from a LEGACY-engine
 * run of record when one exists ({@code oracle-cdc}), otherwise from the recorded fixture pack
 * ({@code recorded}). The report is persisted so the gate can be read back at any time.
 */
@Service
public class PayrollShadowRunner {

  public static final Set<String> EXPLANATIONS =
      Set.of(
          "UNLISTED_STATE_FALLBACK",
          "HEAD_OF_HOUSEHOLD_ZERO_FED",
          "NON_2024_YEAR",
          "LEGACY_PARTIAL_COMMIT",
          "LEGACY_ERROR_ROW");

  private static final TypeReference<List<ShadowLine>> LINES = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final PayrollRunRepository runs;
  private final PayPeriodRepository periods;
  private final RecordedLegacyRows recorded;
  private final TaxRuleRepository taxRules;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final PayrollEngineFlag engineFlag;

  public PayrollShadowRunner(
      JdbcTemplate jdbc,
      PayrollRunRepository runs,
      PayPeriodRepository periods,
      RecordedLegacyRows recorded,
      TaxRuleRepository taxRules,
      ObjectMapper mapper,
      Clock clock,
      PayrollEngineFlag engineFlag) {
    this.jdbc = jdbc;
    this.runs = runs;
    this.periods = periods;
    this.recorded = recorded;
    this.taxRules = taxRules;
    this.mapper = mapper;
    this.clock = clock;
    this.engineFlag = engineFlag;
  }

  record Key(long empId, long elementId) {}

  record Side(
      long empId, long elementId, BigDecimal amount, String status, @Nullable String explanation) {}

  /** Report for the run; recomputed from the current shadow rows on every call. */
  @Transactional
  public ShadowDiffReport report(long runId) {
    RunCore run = runs.core(runId).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    PeriodCore period =
        periods.core(run.periodId()).orElseThrow(() -> new HrmsException(ErrorCode.RUN_NOT_FOUND));
    List<Side> java = javaRows(runId);
    if (java.isEmpty()) {
      throw new HrmsException(
          ErrorCode.SHADOW_REPORT_NOT_FOUND, "No shadow rows for run " + runId, null);
    }
    String legacySource;
    List<Side> legacy = legacyRunOfRecord(run);
    if (!legacy.isEmpty()) {
      legacySource = "oracle-cdc";
    } else {
      legacySource = "recorded";
      legacy = recorded.load(run.periodId()).map(this::fromPack).orElse(List.of());
    }
    ShadowDiffReport report = diff(run, period, legacySource, java, legacy);
    persist(report);
    return report;
  }

  ShadowDiffReport diff(
      RunCore run, PeriodCore period, String legacySource, List<Side> java, List<Side> legacy) {
    Map<Key, Side> javaCalc = new LinkedHashMap<>();
    Map<Long, Side> javaErrors = new HashMap<>();
    for (Side s : java) {
      if ("ERROR".equals(s.status())) {
        javaErrors.put(s.empId(), s);
      } else {
        javaCalc.put(new Key(s.empId(), s.elementId()), s);
      }
    }
    Map<Key, Side> legacyCalc = new LinkedHashMap<>();
    Map<Long, Side> legacyErrors = new HashMap<>();
    for (Side s : legacy) {
      if ("ERROR".equals(s.status())) {
        legacyErrors.put(s.empId(), s);
      } else {
        legacyCalc.put(new Key(s.empId(), s.elementId()), s);
      }
    }

    Set<Key> keys =
        new TreeSet<>(Comparator.comparingLong(Key::empId).thenComparingLong(Key::elementId));
    keys.addAll(javaCalc.keySet());
    keys.addAll(legacyCalc.keySet());
    Set<Long> employees = new HashSet<>();
    employees.addAll(javaErrors.keySet());
    employees.addAll(legacyErrors.keySet());

    Map<Long, String> empNumbers = empNumbers(keys, employees);
    Map<Long, String> elementCodes = elementCodes(keys);
    int taxYear = period.taxYear();

    List<ShadowLine> lines = new ArrayList<>();
    int matched = 0;
    int explained = 0;
    int unexplained = 0;
    int legacyOnly = 0;
    int javaOnly = 0;
    long netDelta = 0;
    for (Key key : keys) {
      employees.add(key.empId());
      Side j = javaCalc.get(key);
      Side l = legacyCalc.get(key);
      long jc = j == null ? 0 : cents(j.amount());
      long lc = l == null ? 0 : cents(l.amount());
      long delta = jc - lc;
      netDelta += delta;
      String classification;
      String explanation = null;
      if (j != null && l != null) {
        if (delta == 0) {
          classification = "MATCH";
          matched++;
        } else {
          explanation = explain(key, l.explanation(), taxYear);
          if (explanation != null) {
            classification = "DIFF_EXPLAINED";
            explained++;
          } else {
            classification = "DIFF_UNEXPLAINED";
            unexplained++;
          }
        }
      } else if (j != null) {
        classification = "JAVA_ONLY";
        javaOnly++;
        explanation = legacyErrors.containsKey(key.empId()) ? "LEGACY_ERROR_ROW" : null;
      } else {
        classification = "LEGACY_ONLY";
        legacyOnly++;
        explanation = javaErrors.containsKey(key.empId()) ? null : l.explanation();
        if (explanation != null && !EXPLANATIONS.contains(explanation)) {
          explanation = null;
        }
      }
      lines.add(
          new ShadowLine(
              key.empId(),
              empNumbers.get(key.empId()),
              key.elementId(),
              elementCodes.get(key.elementId()),
              classification,
              explanation,
              l == null ? null : PayrollDtos.money(l.amount()),
              j == null ? null : PayrollDtos.money(j.amount()),
              delta));
    }
    ShadowSummary summary =
        new ShadowSummary(
            employees.size(),
            matched,
            explained,
            unexplained,
            legacyOnly,
            javaOnly,
            legacyErrors.size(),
            javaErrors.size(),
            netDelta);
    return new ShadowDiffReport(
        run.runId(),
        run.periodId(),
        taxYear,
        engineFlag.current(),
        legacySource,
        LocalDateTime.now(clock).withNano(0),
        summary,
        lines);
  }

  /**
   * Explanation for a cent-level difference: the recorded row's declared explanation when it is on
   * the frozen list, otherwise the structural divergences the target declares (README).
   */
  @Nullable
  String explain(Key key, @Nullable String declared, int taxYear) {
    if (declared != null && EXPLANATIONS.contains(declared)) {
      return declared;
    }
    if (taxYear != 2024) {
      return "NON_2024_YEAR";
    }
    if (key.elementId() == PayrollConstants.STATE_TAX_ELEMENT_ID) {
      Optional<String> state = stateOf(key.empId());
      if (state.isPresent() && taxRules.load(taxYear).stateRate(state.get()).isEmpty()) {
        return "UNLISTED_STATE_FALLBACK";
      }
    }
    if (key.elementId() == PayrollConstants.FED_TAX_ELEMENT_ID
        && filingStatusOf(key.empId()).filter("HEAD_OF_HOUSEHOLD"::equals).isPresent()) {
      return "HEAD_OF_HOUSEHOLD_ZERO_FED";
    }
    return null;
  }

  private List<Side> javaRows(long runId) {
    return jdbc.query(
        "select emp_id, element_id, amount, status from payroll_details_shadow"
            + " where run_id = ? and engine = 'JAVA' order by emp_id, element_id",
        (rs, i) ->
            new Side(
                rs.getLong("emp_id"),
                rs.getLong("element_id"),
                rs.getBigDecimal("amount"),
                rs.getString("status"),
                null),
        runId);
  }

  /** Detail rows of a LEGACY-engine run for the same period (CDC-fed; untested-live here). */
  private List<Side> legacyRunOfRecord(RunCore run) {
    return jdbc.query(
        "select d.emp_id, d.element_id, d.amount, d.status from payroll_details d"
            + " join payroll_runs r on r.run_id = d.run_id"
            + " where r.period_id = ? and r.engine = 'LEGACY' and r.run_id <> ?"
            + " and r.status <> 'REVERSED' order by d.emp_id, d.element_id",
        (rs, i) ->
            new Side(
                rs.getLong("emp_id"),
                rs.getLong("element_id"),
                rs.getBigDecimal("amount"),
                rs.getString("status"),
                null),
        run.periodId(),
        run.runId());
  }

  private List<Side> fromPack(Pack pack) {
    return pack.rows().stream()
        .map(r -> new Side(r.empId(), r.elementId(), r.amountValue(), r.status(), r.explanation()))
        .toList();
  }

  private Map<Long, String> empNumbers(Set<Key> keys, Set<Long> extra) {
    Set<Long> ids = new HashSet<>(extra);
    keys.forEach(k -> ids.add(k.empId()));
    Map<Long, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.query(
        "select emp_id, emp_number from employees where emp_id = any (?)",
        rs -> {
          out.put(rs.getLong("emp_id"), rs.getString("emp_number"));
        },
        (Object) ids.toArray(Long[]::new));
    return out;
  }

  private Map<Long, String> elementCodes(Set<Key> keys) {
    Map<Long, String> out = new HashMap<>();
    if (keys.isEmpty()) {
      return out;
    }
    Long[] ids = keys.stream().map(Key::elementId).distinct().toArray(Long[]::new);
    jdbc.query(
        "select element_id, element_code from pay_elements where element_id = any (?)",
        rs -> {
          out.put(rs.getLong("element_id"), rs.getString("element_code"));
        },
        (Object) ids);
    return out;
  }

  private Optional<String> stateOf(long empId) {
    return jdbc
        .queryForList(
            "select state_code from employee_tax_info where emp_id = ? and active_flag = 'Y'"
                + " order by effective_date desc limit 1",
            String.class,
            empId)
        .stream()
        .findFirst();
  }

  private Optional<String> filingStatusOf(long empId) {
    return jdbc
        .queryForList(
            "select filing_status from employee_tax_info where emp_id = ? and active_flag = 'Y'"
                + " order by effective_date desc limit 1",
            String.class,
            empId)
        .stream()
        .findFirst();
  }

  private void persist(ShadowDiffReport r) {
    String lines;
    try {
      lines = mapper.writeValueAsString(r.lines());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    ShadowSummary s = r.summary();
    jdbc.update(
        "insert into payroll_shadow_reports (run_id, period_id, tax_year, engine_flag,"
            + " legacy_source, compared_at, employees, matched, explained, unexplained,"
            + " legacy_only, java_only, error_rows_legacy, error_rows_java, net_delta_cents,"
            + " lines_json) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (run_id) do update set period_id = excluded.period_id,"
            + " tax_year = excluded.tax_year, engine_flag = excluded.engine_flag,"
            + " legacy_source = excluded.legacy_source, compared_at = excluded.compared_at,"
            + " employees = excluded.employees, matched = excluded.matched,"
            + " explained = excluded.explained, unexplained = excluded.unexplained,"
            + " legacy_only = excluded.legacy_only, java_only = excluded.java_only,"
            + " error_rows_legacy = excluded.error_rows_legacy,"
            + " error_rows_java = excluded.error_rows_java,"
            + " net_delta_cents = excluded.net_delta_cents, lines_json = excluded.lines_json",
        r.runId(),
        r.periodId(),
        r.taxYear(),
        r.engineFlag(),
        r.legacySource(),
        r.comparedAt(),
        s.employees(),
        s.matched(),
        s.explained(),
        s.unexplained(),
        s.legacyOnly(),
        s.javaOnly(),
        s.errorRowsLegacy(),
        s.errorRowsJava(),
        s.netDeltaCents(),
        lines);
  }

  /** Last persisted report, if any (used by tests and the reconciliation pack). */
  public Optional<ShadowDiffReport> persisted(long runId) {
    return jdbc
        .query(
            "select * from payroll_shadow_reports where run_id = ?",
            (rs, i) -> {
              List<ShadowLine> lines;
              try {
                lines = mapper.readValue(rs.getString("lines_json"), LINES);
              } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
              }
              return new ShadowDiffReport(
                  rs.getLong("run_id"),
                  rs.getLong("period_id"),
                  rs.getInt("tax_year"),
                  rs.getString("engine_flag"),
                  rs.getString("legacy_source"),
                  rs.getTimestamp("compared_at").toLocalDateTime(),
                  new ShadowSummary(
                      rs.getInt("employees"),
                      rs.getInt("matched"),
                      rs.getInt("explained"),
                      rs.getInt("unexplained"),
                      rs.getInt("legacy_only"),
                      rs.getInt("java_only"),
                      rs.getInt("error_rows_legacy"),
                      rs.getInt("error_rows_java"),
                      rs.getLong("net_delta_cents")),
                  lines);
            },
            runId)
        .stream()
        .findFirst();
  }

  static long cents(BigDecimal amount) {
    return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
  }
}
