package com.acme.hrms.payroll;

import com.acme.hrms.validation.dto.payroll.PayrollConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start unless the PAY_ELEMENTS rows the TaxEngine writes exist with the frozen
 * code/type and the ERROR sentinel row 0 is present and inactive (contract README). Test contexts
 * that boot before the frozen seed is loaded disable the runner ({@code
 * hrms.payroll.validate-pay-elements=false}) and call {@link #validate()} directly.
 */
@Component
public class PayElementStartupValidator implements ApplicationRunner {

  record Expected(String code, String type) {}

  static final Map<Long, Expected> REQUIRED = new LinkedHashMap<>();

  static {
    REQUIRED.put(PayrollConstants.BASE_PAY_ELEMENT_ID, new Expected("BASE_PAY", "EARNING"));
    REQUIRED.put(PayrollConstants.FED_TAX_ELEMENT_ID, new Expected("FED_TAX", "TAX"));
    REQUIRED.put(PayrollConstants.STATE_TAX_ELEMENT_ID, new Expected("STATE_TAX", "TAX"));
    REQUIRED.put(PayrollConstants.FICA_ELEMENT_ID, new Expected("FICA", "TAX"));
    REQUIRED.put(PayrollConstants.MEDICARE_ELEMENT_ID, new Expected("MEDICARE", "TAX"));
  }

  private final JdbcTemplate jdbc;
  private final boolean enabled;

  public PayElementStartupValidator(
      JdbcTemplate jdbc, @Value("${hrms.payroll.validate-pay-elements:true}") boolean enabled) {
    this.jdbc = jdbc;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (enabled) {
      validate();
    }
  }

  public void validate() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select element_id, element_code, element_type, active_flag from pay_elements"
                + " where element_id in (0, 1, 100, 101, 102, 103)");
    Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      byId.put(((Number) row.get("element_id")).longValue(), row);
    }
    for (Map.Entry<Long, Expected> e : REQUIRED.entrySet()) {
      Map<String, Object> row = byId.get(e.getKey());
      if (row == null
          || !e.getValue().code().equals(row.get("element_code"))
          || !e.getValue().type().equals(row.get("element_type"))
          || !"Y".equals(row.get("active_flag"))) {
        throw new IllegalStateException(
            "PAY_ELEMENTS row "
                + e.getKey()
                + " must be active "
                + e.getValue().code()
                + "/"
                + e.getValue().type()
                + " (found "
                + row
                + ")");
      }
    }
    Map<String, Object> sentinel = byId.get(PayrollConstants.ERROR_ELEMENT_ID);
    if (sentinel == null
        || !"ERROR".equals(sentinel.get("element_type"))
        || !"N".equals(sentinel.get("active_flag"))) {
      throw new IllegalStateException(
          "PAY_ELEMENTS sentinel row 0 must exist as inactive ERROR (found " + sentinel + ")");
    }
  }
}
