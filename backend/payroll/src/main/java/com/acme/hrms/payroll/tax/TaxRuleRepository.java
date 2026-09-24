package com.acme.hrms.payroll.tax;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads TAX_BRACKETS and the {@code TAX} group of SYSTEM_PARAMETERS. Owned by the payroll module;
 * the engine itself is stateless and receives a {@link TaxRules} snapshot per run.
 */
@Repository
public class TaxRuleRepository {

  private final JdbcTemplate jdbc;

  public TaxRuleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public TaxRules load(int taxYear) {
    List<TaxBracket> brackets =
        jdbc.query(
            "select tax_year, filing_status, bracket_min, bracket_max, tax_rate, base_tax,"
                + " state_code from tax_brackets where tax_year = ? and active_flag = 'Y'"
                + " order by state_code nulls first, filing_status, bracket_min",
            (rs, i) ->
                new TaxBracket(
                    rs.getInt("tax_year"),
                    rs.getString("filing_status"),
                    rs.getBigDecimal("bracket_min"),
                    rs.getBigDecimal("bracket_max"),
                    rs.getBigDecimal("tax_rate"),
                    Optional.ofNullable(rs.getBigDecimal("base_tax")).orElse(BigDecimal.ZERO),
                    rs.getString("state_code")),
            taxYear);
    Map<String, BigDecimal> params = new HashMap<>();
    jdbc.query(
        "select param_code, param_value from system_parameters"
            + " where param_group = 'TAX' and param_code like ?",
        rs -> {
          params.put(rs.getString("param_code"), new BigDecimal(rs.getString("param_value")));
        },
        taxYear + ".%");
    return TaxRules.of(taxYear, brackets, params);
  }
}
