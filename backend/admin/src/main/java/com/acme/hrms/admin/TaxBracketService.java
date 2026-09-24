package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.MoneyRange;
import com.acme.hrms.admin.AdminDtos.TaxBracket;
import com.acme.hrms.admin.AdminDtos.TaxLadderGap;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.TaxBracketRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner of TAX_BRACKETS. A federal step is {@code state_code null} + a real filing status; a state
 * row is a 2-letter state + {@code ALL} + {@code [0, +∞)}. Half-open ranges of one active {@code
 * (year, filingStatus, stateCode)} ladder never overlap ({@code -20608}); a year with an {@code
 * APPROVED}/{@code PAID} run is read-only ({@code -20609}). Gaps are reported, not enforced. The
 * payroll {@code TaxRuleRepository} reads per request, so writes need no cache invalidation.
 */
@Service
public class TaxBracketService {
  private static final List<String> FEDERAL_STATUSES =
      List.of("SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD");

  private static final String SELECT =
      """
      select b.*,
             (select count(*) from payroll_runs r join pay_periods p on p.period_id = r.period_id
               where r.status in ('APPROVED', 'PAID')
                 and extract(year from p.period_end_date) = b.tax_year) as locking_runs
        from tax_brackets b
      """;

  private static final RowMapper<TaxBracket> MAPPER =
      (rs, i) ->
          new TaxBracket(
              rs.getLong("bracket_id"),
              rs.getInt("tax_year"),
              rs.getString("filing_status"),
              rs.getString("state_code"),
              AdminDtos.money(rs.getBigDecimal("bracket_min")),
              AdminDtos.money(rs.getBigDecimal("bracket_max")),
              AdminDtos.rate(rs.getBigDecimal("tax_rate")),
              AdminDtos.money(rs.getBigDecimal("base_tax")),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("locking_runs") > 0,
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              null,
              null);

  private final AdminSupport s;

  public TaxBracketService(AdminSupport s) {
    this.s = s;
  }

  public List<TaxBracket> list(
      @Nullable Boolean active,
      @Nullable Integer taxYear,
      @Nullable String stateCode,
      @Nullable String filingStatus) {
    StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
    sql.append(AdminSupport.activeWhere(active, "b.active_flag"));
    Map<String, Object> p = new HashMap<>();
    if (taxYear != null) {
      sql.append(" and b.tax_year = :year");
      p.put("year", taxYear);
    }
    if ("FEDERAL".equals(stateCode)) {
      sql.append(" and b.state_code is null");
    } else if (stateCode != null) {
      sql.append(" and b.state_code = :state");
      p.put("state", stateCode);
    }
    if (filingStatus != null) {
      sql.append(" and b.filing_status = :status");
      p.put("status", filingStatus);
    }
    sql.append(
        " order by b.tax_year desc, b.state_code nulls first, b.filing_status, b.bracket_min,"
            + " b.bracket_id");
    return s.jdbc().query(sql.toString(), p, MAPPER);
  }

  public TaxBracket get(long id) {
    List<TaxBracket> rows =
        s.jdbc().query(SELECT + " where b.bracket_id = :id", Map.of("id", id), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  /** Federal ladders of {@code taxYear} that are not contiguous from 0 to +∞. */
  public List<TaxLadderGap> ladderGaps(int taxYear) {
    List<TaxLadderGap> result = new ArrayList<>();
    for (String status : FEDERAL_STATUSES) {
      List<TaxBracket> ladder =
          s.jdbc()
              .query(
                  SELECT
                      + " where b.tax_year = :year and b.filing_status = :status"
                      + " and b.state_code is null and b.active_flag = 'Y' order by b.bracket_min",
                  Map.of("year", taxYear, "status", status),
                  MAPPER);
      List<MoneyRange> gaps = gapsOf(ladder);
      if (!gaps.isEmpty()) {
        result.add(new TaxLadderGap(taxYear, status, gaps));
      }
    }
    return result;
  }

  private static List<MoneyRange> gapsOf(List<TaxBracket> ladder) {
    List<MoneyRange> gaps = new ArrayList<>();
    BigDecimal cursor = BigDecimal.ZERO;
    for (TaxBracket b : ladder) {
      BigDecimal min = new BigDecimal(b.bracketMin());
      if (cursor == null) {
        break;
      }
      if (min.compareTo(cursor) > 0) {
        gaps.add(new MoneyRange(AdminDtos.money(cursor), AdminDtos.money(min)));
      }
      cursor = b.bracketMax() == null ? null : new BigDecimal(b.bracketMax());
    }
    if (cursor != null) {
      gaps.add(new MoneyRange(AdminDtos.money(cursor), null));
    }
    return gaps;
  }

  @Transactional
  public TaxBracket create(TaxBracketRequest r) {
    requireShape(r);
    requireUnlocked(r.getTaxYear());
    requireNoOverlap(r, null);
    Map<String, Object> p = params(r);
    p.put("actor", s.actor());
    p.put("now", s.now());
    long id =
        s.jdbc()
            .queryForObject(
                """
                insert into tax_brackets (bracket_id, tax_year, filing_status, state_code, bracket_min,
                                          bracket_max, tax_rate, base_tax, active_flag, created_by,
                                          created_date)
                values (nextval('seq_tax_bracket'), :year, :status, :state, :min, :max, :rate, :base,
                        :active, :actor, :now)
                returning bracket_id
                """,
                p,
                Long.class);
    TaxBracket created = get(id);
    s.audit("TAX_BRACKETS", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public TaxBracket update(long id, TaxBracketRequest r) {
    TaxBracket old = get(id);
    requireShape(r);
    requireUnlocked(old.taxYear());
    requireUnlocked(r.getTaxYear());
    requireNoOverlap(r, id);
    Map<String, Object> p = params(r);
    p.put("id", id);
    s.jdbc()
        .update(
            """
            update tax_brackets
               set tax_year = :year, filing_status = :status, state_code = :state,
                   bracket_min = :min, bracket_max = :max, tax_rate = :rate, base_tax = :base,
                   active_flag = :active
             where bracket_id = :id
            """,
            p);
    TaxBracket updated = get(id);
    s.audit("TAX_BRACKETS", id, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(long id) {
    TaxBracket old = get(id);
    requireUnlocked(old.taxYear());
    if (!old.activeFlag()) {
      return;
    }
    s.jdbc()
        .update(
            "update tax_brackets set active_flag = 'N' where bracket_id = :id", Map.of("id", id));
    s.audit("TAX_BRACKETS", id, Action.STATUS_CHANGE, old, get(id));
  }

  private static void requireShape(TaxBracketRequest r) {
    boolean state = r.getStateCode() != null;
    boolean all = "ALL".equals(r.getFilingStatus());
    if (state != all) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_VALUE_RULE,
          state ? "filingStatus" : "stateCode",
          state
              ? "State rows use filingStatus ALL"
              : "Federal steps need a filing status other than ALL");
    }
    if (state && (r.getBracketMin().signum() != 0 || r.getBracketMax() != null)) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_VALUE_RULE, "bracketMin", "State rows cover [0, +∞)");
    }
  }

  private void requireUnlocked(int taxYear) {
    List<Map<String, Object>> runs =
        s.jdbc()
            .queryForList(
                """
                select r.run_id, r.status from payroll_runs r
                  join pay_periods p on p.period_id = r.period_id
                 where r.status in ('APPROVED', 'PAID')
                   and extract(year from p.period_end_date) = :year
                 order by r.run_id limit 1
                """,
                Map.of("year", taxYear));
    if (!runs.isEmpty()) {
      Map<String, Object> run = runs.get(0);
      throw AdminSupport.error(
          ErrorCode.TAX_YEAR_LOCKED,
          "taxYear",
          taxYear,
          ((Number) run.get("run_id")).longValue(),
          run.get("status"));
    }
  }

  private void requireNoOverlap(TaxBracketRequest r, @Nullable Long excludeId) {
    if (!AdminSupport.flag(r.getActiveFlag(), true).equals("Y")) {
      return;
    }
    Map<String, Object> p = new HashMap<>();
    p.put("year", r.getTaxYear());
    p.put("status", r.getFilingStatus());
    p.put("state", r.getStateCode());
    p.put("min", r.getBracketMin());
    p.put("max", r.getBracketMax());
    p.put("id", excludeId == null ? -1L : excludeId);
    List<TaxBracket> hits =
        s.jdbc()
            .query(
                SELECT
                    + """
                     where b.tax_year = :year and b.filing_status = :status
                       and b.state_code is not distinct from cast(:state as varchar)
                       and b.active_flag = 'Y' and b.bracket_id <> :id
                       and (cast(:max as numeric) is null or b.bracket_min < cast(:max as numeric))
                       and (b.bracket_max is null or b.bracket_max > :min)
                     order by b.bracket_min limit 1
                    """,
                p,
                MAPPER);
    if (!hits.isEmpty()) {
      TaxBracket hit = hits.get(0);
      throw AdminSupport.error(
          ErrorCode.TAX_BRACKET_OVERLAP,
          "bracketMin",
          AdminDtos.money(r.getBracketMin()),
          r.getBracketMax() == null ? "+∞" : AdminDtos.money(r.getBracketMax()),
          hit.bracketId(),
          hit.bracketMin(),
          hit.bracketMax() == null ? "+∞" : hit.bracketMax());
    }
  }

  private static Map<String, Object> params(TaxBracketRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("year", r.getTaxYear());
    p.put("status", r.getFilingStatus());
    p.put("state", r.getStateCode());
    p.put("min", r.getBracketMin());
    p.put("max", r.getBracketMax());
    p.put("rate", r.getTaxRate());
    p.put("base", r.getBaseTax() == null ? BigDecimal.ZERO : r.getBaseTax());
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
