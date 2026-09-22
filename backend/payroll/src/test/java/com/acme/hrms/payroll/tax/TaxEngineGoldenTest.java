package com.acme.hrms.payroll.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.payroll.tax.TaxEngine.Input;
import com.acme.hrms.payroll.tax.TaxEngine.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 1 golden-file test: the 2024 rules are read from the migrated PostgreSQL (V7 seed) and
 * every case in {@code tax/2024-golden.json} must reproduce to the cent.
 */
class TaxEngineGoldenTest {

  private static TaxRules rules;
  private static JdbcTemplate jdbc;
  private final TaxEngine engine = new TaxEngine();

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    rules = new TaxRuleRepository(jdbc).load(2024);
  }

  @TestFactory
  List<DynamicTest> goldenCases() throws Exception {
    JsonNode doc =
        new ObjectMapper()
            .readTree(
                Objects.requireNonNull(
                    getClass().getResourceAsStream("/tax/2024-golden.json"), "golden file"));
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : doc.get("cases")) {
      tests.add(
          DynamicTest.dynamicTest(
              c.get("name").asText(),
              () -> {
                Input in =
                    new Input(
                        new BigDecimal(c.get("annualSalary").asText()),
                        c.get("payFrequency").asText(),
                        c.get("filingStatus").asText(),
                        c.get("allowances").asInt(),
                        new BigDecimal(c.get("additionalFedWh").asText()),
                        c.get("stateCode").isNull() ? null : c.get("stateCode").asText(),
                        new BigDecimal(c.get("ytdGross").asText()));
                Result r = engine.calculate(rules, in);
                JsonNode e = c.get("expected");
                assertThat(r.periodGross()).isEqualByComparingTo(e.get("gross").asText());
                assertThat(r.federalTax()).isEqualByComparingTo(e.get("federal").asText());
                assertThat(r.stateTax()).isEqualByComparingTo(e.get("state").asText());
                assertThat(r.socialSecurity())
                    .isEqualByComparingTo(e.get("socialSecurity").asText());
                assertThat(r.medicare()).isEqualByComparingTo(e.get("medicare").asText());
                assertThat(r.federalTax().scale()).isEqualTo(2);
                assertThat(r.stateTax().scale()).isEqualTo(2);
              }));
    }
    assertThat(tests).hasSizeGreaterThanOrEqualTo(27);
    return tests;
  }

  @Test
  void seedHasEveryLadderStateAndScalar() {
    for (String fs : List.of("SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD")) {
      List<TaxBracket> ladder = rules.federalLadder(fs).orElseThrow();
      assertThat(ladder).hasSize(7);
      assertThat(ladder.get(0).min()).isEqualByComparingTo("0");
      assertThat(ladder.get(6).max()).isNull();
      for (int i = 1; i < ladder.size(); i++) {
        assertThat(ladder.get(i).min()).isEqualByComparingTo(ladder.get(i - 1).max());
      }
    }
    assertThat(rules.stateRates().keySet())
        .containsExactlyInAnyOrder("CA", "NY", "TX", "FL", "WA", "IL", "PA", "OH", "NJ", "MA");
    assertThat(rules.scalar("SS_WAGE_BASE")).isEqualByComparingTo("168600");
    assertThat(rules.scalar("ALLOWANCE")).isEqualByComparingTo("4300");
    assertThat(rules.scalar("STD_DEDUCTION.MARRIED_JOINT")).isEqualByComparingTo("29200");
  }

  @Test
  void unlistedStateIsMissingTaxRateNotFivePercent() {
    assertThatThrownBy(() -> engine.stateTax(rules, new BigDecimal("5000.00"), "ZZ"))
        .isInstanceOf(MissingTaxRateException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.MISSING_TAX_RATE);
  }

  @Test
  void nullStateMeansNoStateTaxAndNoError() {
    Result r = engine.calculate(rules, Input.defaults(new BigDecimal("60000"), "MONTHLY", null));
    assertThat(r.stateTax()).isEqualByComparingTo("0");
  }

  @Test
  void nonPositiveSalaryIs20101() {
    for (String salary : List.of("0", "-1")) {
      assertThatThrownBy(() -> engine.periodGross(new BigDecimal(salary), "MONTHLY"))
          .isInstanceOf(HrmsException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.SALARY_NOT_POSITIVE);
    }
    assertThat(ErrorCode.SALARY_NOT_POSITIVE.value()).isEqualTo("-20101");
  }

  @Test
  void unknownFrequencyFallsBackToMonthly() {
    assertThat(TaxEngine.periodsPerYear(null)).isEqualTo(12);
    assertThat(TaxEngine.periodsPerYear("QUARTERLY")).isEqualTo(12);
    assertThat(TaxEngine.periodsPerYear("WEEKLY")).isEqualTo(52);
  }

  @Test
  void taxYearWithoutRulesIsMissingTaxRate() {
    TaxRules empty = new TaxRuleRepository(jdbc).load(2023);
    assertThatThrownBy(
            () -> engine.federalTax(empty, new BigDecimal("5000.00"), "SINGLE", 0, null, "MONTHLY"))
        .isInstanceOf(MissingTaxRateException.class);
  }
}
