package com.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class PayrollApiTest extends IntegrationTest {
    private static final String URL = "/api/v1/payroll";
    private static final String ADMIN = "james.richardson@company.com";
    private static final String MANAGER = "jennifer.park@company.com";
    private static final String EMPLOYEE = "thomas.baker@company.com";

    @Test
    void generatesMonthlyPeriodsIdempotentlyFindsCurrentAndEnforcesClosedGuard() throws Exception {
        String admin = login(ADMIN);
        String manager = login(MANAGER);
        call(get(URL + "/periods/current"), manager).andExpect(status().isNotFound());
        JsonNode periods = createPeriods(admin, 2026, "MONTHLY");
        assertThat(periods).hasSize(12);
        assertThat(createPeriods(admin, 2026, "MONTHLY")).isEqualTo(periods);
        assertThat(periods.get(0).get("payDate").asText()).isEqualTo("2026-01-30");
        assertThat(periods.get(4).get("payDate").asText()).isEqualTo("2026-05-29");

        long november = periodId(periods, 11);
        call(get(URL + "/periods/current"), manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.periodId").value(november))
                .andExpect(jsonPath("$.startDate").value("2026-11-01"));
        call(post(URL + "/periods/{id}/close", november), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        expectCode(call(post(URL + "/periods/{id}/close", november), admin), 409, "-20102");
        expectCode(callJson(post(URL + "/runs"), admin, Map.of("periodId", november)), 409, "-20102");
        call(get(URL + "/periods").param("year", "2026").param("status", "CLOSED"), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        call(get(URL + "/periods").param("year", "2025"), manager)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'PAY_PERIODS'", Integer.class))
                .isEqualTo(13);
    }

    @Test
    void biweeklyPeriodsStartBeforeFirstFridayAndRemainDistinctFromMonthly() throws Exception {
        String admin = login(ADMIN);
        JsonNode biweekly = createPeriods(admin, 2026, "BIWEEKLY");
        assertThat(biweekly).hasSize(27);
        JsonNode first = biweekly.get(0);
        assertThat(first.get("startDate").asText()).isEqualTo("2025-12-20");
        assertThat(first.get("endDate").asText()).isEqualTo("2026-01-02");
        assertThat(first.get("payDate").asText()).isEqualTo("2026-01-07");
        assertThat(createPeriods(admin, 2026, "BIWEEKLY")).isEqualTo(biweekly);
        createPeriods(admin, 2026, "MONTHLY");
        call(get(URL + "/periods/current"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("MONTHLY"));
        expectCode(callJson(post(URL + "/periods"), admin, Map.of("year", 2026, "frequency", "WEEKLY")), 400, "-20000");
    }

    @Test
    void enforcesLifecycleReturnsAllRunsAndExcludesReversedPayFromYtd() throws Exception {
        String admin = login(ADMIN);
        long period = periodId(createPeriods(admin, 2026, "MONTHLY"), 11);
        long run = createRun(admin, period, "OFF_CYCLE");

        call(get(URL + "/runs").param("periodId", String.valueOf(period)), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runType").value("OFF_CYCLE"));
        call(get(URL + "/runs/{id}", run), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        expectCode(call(post(URL + "/runs/{id}/approve", run), admin), 409, "-20103");
        call(post(URL + "/runs/{id}/calculate", run), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.calculatedAt").value("2026-11-19T09:00:00"));
        JsonNode lines = details(admin, run, 22);
        assertThat(lines.get(0).get("elementCode").asText()).isEqualTo("BASE_PAY");
        assertThat(lines.get(0).get("amount").decimalValue()).isEqualByComparingTo("7916.67");
        expectCode(call(post(URL + "/runs/{id}/calculate", run), admin), 409, "-20103");
        assertThat(details(admin, run, 22)).isEqualTo(lines);
        call(post(URL + "/runs/{id}/approve", run), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(ADMIN));
        expectCode(call(post(URL + "/runs/{id}/approve", run), admin), 409, "-20103");

        call(get(URL + "/employees/22/ytd"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(7916.67));
        call(post(URL + "/runs/{id}/reverse", run), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(details(admin, run, 22)).allSatisfy(line -> assertThat(line.get("status").asText()).isEqualTo("REVERSED"));
        call(get(URL + "/employees/22/ytd"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(0));
        call(get(URL + "/payslips/{id}/22", run), admin).andExpect(status().isNotFound());
        call(post(URL + "/runs/{id}/reverse", run), admin).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'PAYROLL_RUNS' AND record_id = ?",
                Integer.class, run)).isEqualTo(4);
    }

    @Test
    void usesSeededHeadOfHouseholdBracketsW4AndEmployeeDeductionPrecedence() throws Exception {
        String admin = login(ADMIN);
        String owner = login(EMPLOYEE);
        jdbc.update("""
                INSERT INTO employee_tax_info (emp_id, tax_year, filing_status, federal_allowances,
                    additional_fed_wh, state_code, created_by)
                VALUES (22, 2026, 'HEAD_OF_HOUSEHOLD', 1, 15, 'CA', 'test')
                """);
        jdbc.update("""
                INSERT INTO employee_pay_elements (emp_id, element_id, effective_date, amount, percentage, override_amount, created_by)
                VALUES (22, 200, DATE '2026-01-01', 100, 4, NULL, 'test'),
                       (22, 201, DATE '2026-01-01', 80, NULL, 25, 'test'),
                       (22, 202, DATE '2026-01-01', NULL, NULL, NULL, 'test'),
                       (22, 203, DATE '2027-01-01', NULL, NULL, NULL, 'test')
                """);

        call(get(URL + "/tax-brackets").param("year", "2024").param("filingStatus", "HEAD_OF_HOUSEHOLD"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
        call(get(URL + "/employees/22/tax-info").param("year", "2026"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.filingStatus").value("HEAD_OF_HOUSEHOLD"));
        call(get(URL + "/employees/22/tax-info").param("year", "2025"), owner).andExpect(status().isNotFound());
        call(get(URL + "/employees/22/pay-elements"), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].elementId").value(org.hamcrest.Matchers.contains(200, 201, 202)));

        long period = periodId(createPeriods(admin, 2026, "MONTHLY"), 11);
        long run = createRun(admin, period, "REGULAR");
        JsonNode result = calculate(admin, run);
        assertThat(result.get("totalGross").decimalValue().subtract(result.get("totalDeductions").decimalValue()))
                .isEqualByComparingTo(result.get("totalNet").decimalValue());
        JsonNode lines = details(admin, run, 22);
        assertAmount(lines, "FED_TAX", "-856.75");
        assertAmount(lines, "STATE_TAX", "-573.96");
        assertAmount(lines, "401K_EE", "-316.67");
        assertAmount(lines, "MED_EE", "-25.00");
        assertAmount(lines, "DENTAL_EE", "-45.00");
        assertThat(lines).noneMatch(line -> "VISION_EE".equals(line.path("elementCode").asText()));
        JsonNode slip = read(call(get(URL + "/payslips/{id}/22", run), owner).andExpect(status().isOk()));
        assertThat(slip.get("deductions").decimalValue().subtract(slip.get("taxes").decimalValue()))
                .isEqualByComparingTo("386.67");
        assertThat(slip.get("gross").decimalValue()).isEqualByComparingTo(slip.get("ytdGross").decimalValue());
        assertThat(slip.get("net").decimalValue()).isEqualByComparingTo(slip.get("ytdNet").decimalValue());
    }

    @Test
    void capsFicaAndAdditionalMedicareWithPriorGrossAndComputesHistoricalPayslipYtd() throws Exception {
        String admin = login(ADMIN);
        JsonNode periods = createPeriods(admin, 2026, "MONTHLY");
        long january = createRun(admin, periodId(periods, 1), "REGULAR");
        jdbc.update("UPDATE payroll_runs SET status = 'APPROVED' WHERE run_id = ?", january);
        jdbc.update("""
                INSERT INTO payroll_details (run_id, emp_id, element_id, element_type, amount, created_by)
                VALUES (?, 1, 1, 'EARNING', 165000, 'test')
                """, january);
        long february = createRun(admin, periodId(periods, 2), "REGULAR");
        calculate(admin, february);
        JsonNode februaryLines = details(admin, february, 1);
        assertAmount(februaryLines, "FICA", "-223.20");
        assertAmount(februaryLines, "MEDICARE", "-566.25");

        long march = createRun(admin, periodId(periods, 3), "REGULAR");
        calculate(admin, march);
        JsonNode marchLines = details(admin, march, 1);
        assertThat(marchLines).noneMatch(line -> "FICA".equals(line.path("elementCode").asText()));
        assertAmount(marchLines, "MEDICARE", "-881.25");
        call(get(URL + "/payslips/{id}/1", february), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(37500))
                .andExpect(jsonPath("$.ytdGross").value(202500));
        call(get(URL + "/employees/1/ytd").param("year", "2026"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(240000));
    }

    @Test
    void missingEffectiveSalaryProducesOneNullableElementErrorAndKeepsOtherEmployees() throws Exception {
        String admin = login(ADMIN);
        jdbc.update("UPDATE salary_records SET effective_date = DATE '2026-12-01' WHERE emp_id = 22");
        long period = periodId(createPeriods(admin, 2026, "MONTHLY"), 11);
        long run = createRun(admin, period, "CORRECTION");
        assertThat(calculate(admin, run).get("status").asText()).isEqualTo("ERROR");
        JsonNode errors = details(admin, run, 22);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("elementId").isMissingNode()).isTrue();
        assertThat(errors.get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(errors.get(0).get("errorMessage").asText()).startsWith("-20104");
        assertThat(details(admin, run, 1)).isNotEmpty();
        call(get(URL + "/payslips/{id}/1", run), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.ytdGross").value(37500));
        call(get(URL + "/employees/1/ytd"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(0));
        expectCode(call(post(URL + "/runs/{id}/approve", run), admin), 409, "-20103");
        expectCode(call(post(URL + "/runs/{id}/calculate", run), admin), 409, "-20103");
    }

    @Test
    void delegatesSalaryChangeRecordsHistoryAndUsesSalaryEffectiveAtPeriodEnd() throws Exception {
        String admin = login(ADMIN);
        Map<String, Object> change = Map.of("baseSalary", 120000, "effectiveDate", "2026-12-01",
                "changeReason", "Annual review", "payFrequency", "BIWEEKLY");
        expectCode(callJson(post(URL + "/employees/22/salary"), admin, Map.of("baseSalary", 0,
                "effectiveDate", "2026-12-01", "changeReason", "Annual review")), 422, "-20101");
        callJson(post(URL + "/employees/22/salary"), admin, change).andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseSalary").value(120000))
                .andExpect(jsonPath("$.payFrequency").value("BIWEEKLY"))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT end_date FROM salary_records WHERE salary_id = 22", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE table_name = 'SALARY_RECORDS'", Integer.class))
                .isEqualTo(2);
        expectCode(callJson(post(URL + "/employees/22/salary"), admin, Map.of("baseSalary", 100000,
                "effectiveDate", "2026-10-01", "changeReason", "Invalid chronology")), 409, "-20991");

        JsonNode periods = createPeriods(admin, 2026, "MONTHLY");
        long before = createRun(admin, periodId(periods, 11), "REGULAR");
        long after = createRun(admin, periodId(periods, 12), "REGULAR");
        calculate(admin, before);
        calculate(admin, after);
        assertAmount(details(admin, before, 22), "BASE_PAY", "7916.67");
        assertAmount(details(admin, after, 22), "BASE_PAY", "10000.00");
    }

    @Test
    void downloadsLegacyRegisterGlJournalAndFixedWidthBenefits() throws Exception {
        String admin = login(ADMIN);
        String manager = login(MANAGER);
        jdbc.update("UPDATE departments SET dept_name = 'Finance, \"West\"' WHERE dept_id = 20");
        jdbc.update("""
                INSERT INTO employee_dependents (emp_id, first_name, last_name, relationship, date_of_birth, created_by)
                VALUES (1, 'KID', 'RICHARDSON', 'CHILD', DATE '2015-01-01', 'test')
                """);
        long period = periodId(createPeriods(admin, 2026, "MONTHLY"), 11);
        long run = createRun(admin, period, "BONUS");
        calculate(admin, run);

        String csv = call(get(URL + "/runs/{id}/register.csv", run), manager).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"PAY_REGISTER_" + run + ".csv\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("EMP_NUMBER,EMPLOYEE_NAME,DEPARTMENT,GROSS_PAY,FED_TAX")
                .contains("\"Finance, \"\"West\"\"\"").contains("THOMAS BAKER");
        String gl = call(get(URL + "/runs/{id}/gl-journal", run), manager).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"GL_JOURNAL_" + run + "_20261119.dat\""))
                .andReturn().getResponse().getContentAsString();
        var glRows = gl.lines().toList();
        assertThat(glRows.get(0)).isEqualTo("H|HRMS_PAYROLL|2026-11-19|" + run);
        assertThat(gl).contains("|5100-100|", "|2100-100|", "|Payroll 2026-11 (Nov)|RUN-");
        assertThat(glRows.get(glRows.size() - 1)).isEqualTo("T|" + (glRows.size() - 2));

        String benefits = call(get(URL + "/exports/benefits"), manager).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"BENEFITS_20261119.txt\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(benefits.lines().toList()).allSatisfy(line -> assertThat(line).hasSize(203));
        assertThat(benefits).contains("KID", "2015-01-01", "EMP-000022").doesNotContain("EMP-000099");
    }

    @Test
    void enforcesPayrollGradesAndSelfScopedReads() throws Exception {
        String admin = login(ADMIN);
        String manager = login(MANAGER);
        String employee = login(EMPLOYEE);
        long period = periodId(createPeriods(admin, 2026, "MONTHLY"), 11);
        long run = createRun(admin, period, "REGULAR");
        calculate(admin, run);

        for (String path : new String[] {"/periods", "/periods/current", "/runs", "/runs/" + run,
                "/runs/" + run + "/details?empId=22", "/runs/" + run + "/register.csv", "/runs/" + run + "/gl-journal",
                "/exports/benefits", "/payslips/" + run + "/1", "/employees/1/ytd", "/employees/1/pay-elements",
                "/employees/1/tax-info", "/tax-brackets?year=2024"}) {
            expectCode(call(get(URL + path), employee), 403, "-20304");
        }
        for (String path : new String[] {"/periods/" + period + "/close", "/runs/" + run + "/calculate",
                "/runs/" + run + "/approve", "/runs/" + run + "/reverse"}) {
            expectCode(call(post(URL + path), manager), 403, "-20304");
        }
        expectCode(callJson(post(URL + "/periods"), manager, Map.of("year", 2026, "frequency", "MONTHLY")), 403, "-20304");
        expectCode(callJson(post(URL + "/runs"), manager, Map.of("periodId", period)), 403, "-20304");
        expectCode(callJson(post(URL + "/employees/22/salary"), manager,
                Map.of("baseSalary", 100000, "effectiveDate", "2027-01-01", "changeReason", "Review")), 403, "-20304");
        call(get(URL + "/payslips/{id}/22", run), employee).andExpect(status().isOk());
        call(get(URL + "/payslips/{id}/22", run), manager).andExpect(status().isOk());
        call(get(URL + "/runs/-1"), manager).andExpect(status().isNotFound());
        call(get(URL + "/tax-brackets"), manager).andExpect(status().isBadRequest());
    }

    private JsonNode createPeriods(String token, int year, String frequency) throws Exception {
        return read(callJson(post(URL + "/periods"), token, Map.of("year", year, "frequency", frequency))
                .andExpect(status().isCreated()));
    }

    private static long periodId(JsonNode periods, int month) {
        return periods.get(month - 1).get("periodId").asLong();
    }

    private long createRun(String token, long period, String type) throws Exception {
        return read(callJson(post(URL + "/runs"), token, Map.of("periodId", period, "runType", type))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")))
                .get("runId").asLong();
    }

    private JsonNode calculate(String token, long run) throws Exception {
        return read(call(post(URL + "/runs/{id}/calculate", run), token).andExpect(status().isOk()));
    }

    private JsonNode details(String token, long run, long employee) throws Exception {
        return read(call(get(URL + "/runs/{id}/details", run).param("empId", String.valueOf(employee)), token)
                .andExpect(status().isOk()));
    }

    private static void expectCode(ResultActions result, int statusCode, String errorCode) throws Exception {
        result.andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(errorCode));
    }

    private static void assertAmount(JsonNode lines, String code, String amount) {
        assertThat(lines).filteredOn(line -> code.equals(line.path("elementCode").asText()))
                .singleElement().satisfies(line -> assertThat(line.get("amount").decimalValue())
                        .isEqualByComparingTo(new BigDecimal(amount)));
    }
}
