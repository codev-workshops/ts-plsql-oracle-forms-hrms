package com.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.auth.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P6 payroll. "Today" is 2026-03-02 (UTC). Emp 1 (grade 10) has every permission; emp 31 (grade 6) has only
 * PAYROLL:VIEW; emp 37 (grade 2) has no payroll access. All 23 active seed employees have a MONTHLY salary.
 */
class PayrollApiTest extends ApiTestSupport {

    @Autowired
    PayrollBatch batch;

    String admin;
    String viewer;
    String nobody;

    @BeforeEach
    void reset() throws Exception {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
        jdbc.update("DELETE FROM hrms.payroll_details");
        jdbc.update("DELETE FROM hrms.payroll_runs");
        jdbc.update("DELETE FROM hrms.pay_periods");
        jdbc.update("DELETE FROM hrms.employee_tax_info");
        jdbc.update("DELETE FROM hrms.employee_pay_elements");
        jdbc.update("DELETE FROM hrms.audit_log WHERE table_name IN ('PAYROLL_RUNS', 'PAY_PERIODS')");
        jdbc.update("UPDATE hrms.salary_records SET end_date = NULL, active_flag = true WHERE emp_id = 36");
        admin = tokenFor(1);
        viewer = tokenFor(31);
        nobody = tokenFor(37);
    }

    @Test
    void generatesMonthlyAndBiweeklyPeriodsIdempotently() throws Exception {
        JsonNode g = read(send(admin, post("/api/v1/payroll/pay-periods/generate"),
                Map.of("year", 2026, "frequency", "MONTHLY")).andExpect(status().isCreated()));
        assertThat(g.get("created")).hasSize(12);
        assertThat(g.get("skipped").asInt()).isZero();
        JsonNode jan = g.get("created").get(0);
        assertThat(jan.get("periodName").asText()).isEqualTo("2026-01 (Jan)");
        assertThat(jan.get("periodEndDate").asText()).isEqualTo("2026-01-31");
        assertThat(jan.get("payDate").asText()).isEqualTo("2026-01-30"); // Saturday -> Friday
        assertThat(g.get("created").get(4).get("payDate").asText()).isEqualTo("2026-05-29"); // Sunday -> Friday
        assertThat(g.get("created").get(2).get("payDate").asText()).isEqualTo("2026-03-31");
        assertThat(jan.get("status").asText()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT created_by FROM hrms.pay_periods WHERE period_id = ?", String.class,
                jan.get("periodId").asLong())).isEqualTo("james.richardson@company.com");

        JsonNode again = read(send(admin, post("/api/v1/payroll/pay-periods/generate"),
                Map.of("year", 2026, "frequency", "MONTHLY")).andExpect(status().isOk()));
        assertThat(again.get("created")).isEmpty();
        assertThat(again.get("skipped").asInt()).isEqualTo(12); // DEF-P6-3: no duplicates on re-run

        JsonNode bw = read(send(admin, post("/api/v1/payroll/pay-periods/generate"),
                Map.of("year", 2026, "frequency", "BIWEEKLY")).andExpect(status().isCreated()));
        assertThat(bw.get("created")).hasSize(27);
        JsonNode first = bw.get("created").get(0);
        assertThat(first.get("periodName").asText()).isEqualTo("2026-BW-01");
        assertThat(first.get("periodStartDate").asText()).isEqualTo("2025-12-20");
        assertThat(first.get("periodEndDate").asText()).isEqualTo("2026-01-02"); // the year's first Friday
        assertThat(first.get("payDate").asText()).isEqualTo("2026-01-07"); // end + 5
        assertThat(bw.get("created").get(26).get("periodStartDate").asText()).isEqualTo("2026-12-19");
        JsonNode next = read(send(admin, post("/api/v1/payroll/pay-periods/generate"),
                Map.of("year", 2027, "frequency", "BIWEEKLY")).andExpect(status().isCreated()));
        assertThat(next.get("skipped").asInt()).isEqualTo(1); // 2026-12-19..2027-01-01 already exists
        assertThat(next.get("created").get(0).get("periodStartDate").asText()).isEqualTo("2027-01-02");

        expect(send(admin, post("/api/v1/payroll/pay-periods/generate"), Map.of("year", 2026, "frequency",
                "SEMIMONTHLY")), 400, "VALIDATION_FAILED", null);
        expect(send(viewer, post("/api/v1/payroll/pay-periods/generate"), Map.of("year", 2028, "frequency",
                "MONTHLY")), 403, "FORBIDDEN", null);

        // FRM-061: OPEN periods by default, newest first; FRM-060: PAYROLL:VIEW
        JsonNode monthly = read(as(viewer, get("/api/v1/payroll/pay-periods?frequency=MONTHLY&year=2026"))
                .andExpect(status().isOk()));
        assertThat(monthly).hasSize(12);
        assertThat(monthly.get(0).get("periodName").asText()).isEqualTo("2026-12 (Dec)");
        long march = periodId("2026-03 (Mar)");
        send(admin, post("/api/v1/payroll/pay-periods/" + march + "/close"), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedBy").value("james.richardson@company.com"));
        assertThat(read(as(viewer, get("/api/v1/payroll/pay-periods?frequency=MONTHLY&year=2026")))).hasSize(11);
        assertThat(read(as(viewer, get("/api/v1/payroll/pay-periods?status=CLOSED")))).hasSize(1);
        expect(send(admin, post("/api/v1/payroll/pay-periods/" + march + "/close"), Map.of()), 409, "PERIOD_CLOSED",
                -20102);
        expect(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", march)), 409, "PERIOD_CLOSED", -20102);
        expect(as(nobody, get("/api/v1/payroll/pay-periods")), 403, "FORBIDDEN", null);
        expect(as(viewer, get("/api/v1/payroll/pay-periods/999999")), 404, "NOT_FOUND", null);
    }

    @Test
    void runLifecycleEnforcesTheGatesServerSide() throws Exception {
        long march = monthlyPeriods("2026-03 (Mar)");
        expect(send(viewer, post("/api/v1/payroll/runs"), Map.of("periodId", march)), 403, "FORBIDDEN", null);
        JsonNode run = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", march))
                .andExpect(status().isCreated()));
        long runId = run.get("runId").asLong();
        assertThat(run.get("status").asText()).isEqualTo("PENDING");
        assertThat(run.get("runType").asText()).isEqualTo("REGULAR");
        assertThat(run.get("submittedBy").asText()).isEqualTo("james.richardson@company.com");

        expect(command(admin, runId, "approve"), 409, "INVALID_RUN_STATUS", -20103);
        expect(command(admin, runId, "reverse", Map.of("reason", "x")), 409, "INVALID_RUN_STATUS", null);
        expect(command(viewer, runId, "calculate"), 403, "FORBIDDEN", null);

        JsonNode calc = read(command(admin, runId, "calculate").andExpect(status().isOk()));
        assertThat(calc.get("status").asText()).isEqualTo("CALCULATED");
        assertThat(calc.get("employeeCount").asInt()).isEqualTo(23);
        assertThat(calc.get("errorCount").asInt()).isZero();
        // FRM-062: the Forms-only PENDING gate is now a server rule
        expect(command(admin, runId, "calculate"), 409, "INVALID_RUN_STATUS", null);

        // emp 35: 78000 / 12 = 6500.00; SINGLE, no W-4 on file, no state
        JsonNode slip = read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/payslips?empId=35"))
                .andExpect(status().isOk())).get(0);
        assertThat(slip.get("grossPay").decimalValue()).isEqualByComparingTo("6500.00");
        assertThat(slip.get("federalTax").decimalValue()).isEqualByComparingTo("750.08");
        assertThat(slip.get("stateTax").decimalValue()).isEqualByComparingTo("0");
        assertThat(slip.get("socialSecurity").decimalValue()).isEqualByComparingTo("403.00");
        assertThat(slip.get("medicare").decimalValue()).isEqualByComparingTo("94.25");
        assertThat(slip.get("totalDeductions").decimalValue()).isEqualByComparingTo("1247.33");
        assertThat(slip.get("netPay").decimalValue()).isEqualByComparingTo("5252.67");
        assertThat(slip.get("ytdGross").decimalValue()).isEqualByComparingTo("6500.00"); // DEF-06: no placeholder
        assertThat(slip.get("payDate").asText()).isEqualTo("2026-03-31");
        assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/payslips")))).hasSize(23);

        expect(command(viewer, runId, "approve"), 403, "FORBIDDEN", null);
        command(admin, runId, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("james.richardson@company.com"));
        expect(command(admin, runId, "approve"), 409, "INVALID_RUN_STATUS", -20103);

        expect(command(admin, runId, "reverse", Map.of("reason", " ")), 400, "VALIDATION_FAILED", null);
        command(admin, runId, "reverse", Map.of("reason", "Wrong period")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.payroll_details WHERE run_id = ? AND status <> "
                + "'REVERSED'", Long.class, runId)).isZero();
        expect(command(admin, runId, "reverse", Map.of("reason", "again")), 409, "INVALID_RUN_STATUS", null);
        assertThat(jdbc.queryForList("SELECT new_values->>'status' FROM hrms.audit_log WHERE table_name = "
                + "'PAYROLL_RUNS' AND record_id = ? ORDER BY audit_id", String.class, runId))
                .containsExactly("PENDING", "CALCULATED", "APPROVED", "REVERSED");
        assertThat(jdbc.queryForObject("SELECT new_values->>'reason' FROM hrms.audit_log WHERE table_name = "
                + "'PAYROLL_RUNS' AND record_id = ? AND new_values->>'status' = 'REVERSED'", String.class, runId))
                .isEqualTo("Wrong period");

        assertThat(read(as(viewer, get("/api/v1/payroll/runs?periodId=" + march)))).hasSize(1);
        assertThat(read(as(viewer, get("/api/v1/payroll/runs?status=PENDING")))).isEmpty();
        expect(as(viewer, get("/api/v1/payroll/runs/999999")), 404, "NOT_FOUND", null);
        expect(command(admin, 999999, "calculate"), 404, "NOT_FOUND", null);
        expect(as(nobody, get("/api/v1/payroll/runs")), 403, "FORBIDDEN", null);
    }

    @Test
    void taxesDeductionsAndTotalsAreTableDriven() throws Exception {
        long march = monthlyPeriods("2026-03 (Mar)");
        jdbc.update("""
                INSERT INTO hrms.employee_tax_info (emp_id, tax_year, filing_status, federal_allowances,
                    additional_fed_wh, state_code, created_by)
                VALUES (34, 2026, 'MARRIED_JOINT', 2, 25, 'IL', 'test')
                """);
        jdbc.update("""
                INSERT INTO hrms.employee_pay_elements (emp_id, element_id, effective_date, override_amount, created_by)
                VALUES (34, 200, DATE '2025-01-01', NULL, 'test'), (34, 201, DATE '2025-01-01', NULL, 'test'),
                       (34, 202, DATE '2025-01-01', 30, 'test'), (34, 203, DATE '2026-04-01', NULL, 'test')
                """);
        long runId = createAndCalculate(march);

        // 82000 / 12 = 6833.33; MARRIED_JOINT: (81999.96 - 29200 - 2 * 4300) -> 2320 + 12% over 23200 = 4839.9952
        // per year -> 403.33 + 25 additional; IL 4.95%; 401(k) 6%; medical 250 (default); dental override 30;
        // vision starts after the period
        JsonNode slip = read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/payslips?empId=34"))).get(0);
        assertThat(slip.get("grossPay").decimalValue()).isEqualByComparingTo("6833.33");
        assertThat(slip.get("federalTax").decimalValue()).isEqualByComparingTo("428.33");
        assertThat(slip.get("stateTax").decimalValue()).isEqualByComparingTo("338.25");
        assertThat(slip.get("socialSecurity").decimalValue()).isEqualByComparingTo("423.67");
        assertThat(slip.get("medicare").decimalValue()).isEqualByComparingTo("99.08");
        assertThat(slip.get("totalDeductions").decimalValue()).isEqualByComparingTo("1979.33");
        assertThat(slip.get("netPay").decimalValue()).isEqualByComparingTo("4854.00");
        assertThat(jdbc.queryForList("SELECT element_id FROM hrms.payroll_details WHERE run_id = ? AND emp_id = 34 "
                + "ORDER BY detail_id", Long.class, runId)).containsExactly(1L, 100L, 101L, 102L, 103L, 200L, 201L, 202L);

        // DEF-P6-1: run totals include BENEFIT deductions, so they agree with the payslips
        JsonNode run = read(as(viewer, get("/api/v1/payroll/runs/" + runId)));
        Map<String, Object> sums = jdbc.queryForMap("""
                SELECT sum(CASE WHEN element_type = 'EARNING' THEN amount ELSE 0 END) AS gross,
                       sum(CASE WHEN element_type <> 'EARNING' THEN abs(amount) ELSE 0 END) AS ded,
                       sum(amount) AS net
                FROM hrms.payroll_details WHERE run_id = ?
                """, runId);
        assertThat(run.get("totalGross").decimalValue()).isEqualByComparingTo((BigDecimal) sums.get("gross"));
        assertThat(run.get("totalDeductions").decimalValue()).isEqualByComparingTo((BigDecimal) sums.get("ded"));
        assertThat(run.get("totalNet").decimalValue()).isEqualByComparingTo((BigDecimal) sums.get("net"));

        // DEF-05: the brackets are data. A 2026 bracket set replaces the 2024 fallback for MARRIED_JOINT.
        jdbc.update("""
                INSERT INTO hrms.tax_brackets (tax_year, filing_status, bracket_min, bracket_max, tax_rate, base_tax,
                    created_by)
                VALUES (2026, 'MARRIED_JOINT', 0, NULL, 0.10, 0, 'test')
                """);
        try {
            long april = periodId("2026-04 (Apr)");
            long second = createAndCalculate(april);
            // 44199.96 * 10% / 12 = 368.33 + 25
            assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + second + "/payslips?empId=34"))).get(0)
                    .get("federalTax").decimalValue()).isEqualByComparingTo("393.33");
        } finally {
            jdbc.update("DELETE FROM hrms.tax_brackets WHERE tax_year = 2026");
        }
    }

    @Test
    void missingSalaryMakesTheRunErrorAndAFailureRollsTheWholeRunBack() throws Exception {
        long march = monthlyPeriods("2026-03 (Mar)");
        jdbc.update("UPDATE hrms.salary_records SET end_date = DATE '2026-02-28', active_flag = false WHERE emp_id = 36");
        long runId = createAndCalculate(march);
        JsonNode run = read(as(viewer, get("/api/v1/payroll/runs/" + runId)));
        assertThat(run.get("status").asText()).isEqualTo("ERROR");
        assertThat(run.get("employeeCount").asInt()).isEqualTo(22);
        assertThat(run.get("errorCount").asInt()).isEqualTo(1);
        JsonNode errors = read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/errors")).andExpect(status().isOk()));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("empId").asLong()).isEqualTo(36);
        assertThat(errors.get(0).get("message").asText()).startsWith("NO_ACTIVE_SALARY (-20104)");
        expect(command(admin, runId, "approve"), 409, "INVALID_RUN_STATUS", -20103);
        command(admin, runId, "reverse", Map.of("reason", "Fix salary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/errors")))).hasSize(1);
        assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + runId + "/payslips")))).hasSize(22);

        // DEF-16: one transaction. A failure in the middle of the first employee (missing MEDICARE_RATE, after
        // the earning and other tax rows were written) leaves the run PENDING with no details.
        jdbc.update("UPDATE hrms.payroll_tax_parameters SET tax_year = 2023 WHERE param_code = 'MEDICARE_RATE'");
        try {
            long second = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", march))
                    .andExpect(status().isCreated())).get("runId").asLong();
            command(admin, second, "calculate").andExpect(status().isInternalServerError());
            assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + second))).get("status").asText())
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.payroll_details WHERE run_id = ?", Long.class,
                    second)).isZero();
        } finally {
            jdbc.update("UPDATE hrms.payroll_tax_parameters SET tax_year = 2024 WHERE param_code = 'MEDICARE_RATE'");
        }
    }

    @Test
    void yearToDateCapsSocialSecurityAndAddsMedicare() throws Exception {
        monthlyPeriods("2026-01 (Jan)");
        String[] months = {"2026-01 (Jan)", "2026-02 (Feb)", "2026-03 (Mar)", "2026-04 (Apr)", "2026-05 (May)",
                "2026-06 (Jun)"};
        long last = 0;
        JsonNode may = null;
        for (String m : months) {
            last = createAndCalculate(periodId(m));
            if (m.startsWith("2026-05")) {
                may = read(as(viewer, get("/api/v1/payroll/runs/" + last + "/payslips?empId=1"))).get(0);
            }
            command(admin, last, "approve").andExpect(status().isOk());
        }
        // emp 1: 450000 / 12 = 37500. After Jan-Apr, 150000 YTD leaves 18600 under the 168600 wage base.
        assertThat(may.get("socialSecurity").decimalValue()).isEqualByComparingTo("1153.20");
        assertThat(may.get("medicare").decimalValue()).isEqualByComparingTo("543.75");
        JsonNode june = read(as(viewer, get("/api/v1/payroll/runs/" + last + "/payslips?empId=1"))).get(0);
        assertThat(june.get("socialSecurity").decimalValue()).isEqualByComparingTo("0");
        // 187500 + 37500 - 200000 = 25000 over the threshold at 0.9%
        assertThat(june.get("medicare").decimalValue()).isEqualByComparingTo("768.75");
        assertThat(june.get("ytdGross").decimalValue()).isEqualByComparingTo("225000.00");
        // A reversed run no longer counts towards YTD
        command(admin, last, "reverse", Map.of("reason", "redo")).andExpect(status().isOk());
        long redo = createAndCalculate(periodId("2026-06 (Jun)"));
        assertThat(read(as(viewer, get("/api/v1/payroll/runs/" + redo + "/payslips?empId=1"))).get(0)
                .get("medicare").decimalValue()).isEqualByComparingTo("768.75");
    }

    @Test
    void batchJobCalculatesEveryPendingRun() throws Exception {
        long jan = monthlyPeriods("2026-01 (Jan)");
        long a = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", jan))).get("runId").asLong();
        long b = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", periodId("2026-02 (Feb)"),
                "runType", "SUPPLEMENTAL"))).get("runId").asLong();
        assertThat(batch.calculatePendingRuns()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM hrms.payroll_runs WHERE run_id IN (?, ?)", String.class, a, b))
                .containsOnly("CALCULATED");
        assertThat(jdbc.queryForObject("SELECT modified_by FROM hrms.payroll_runs WHERE run_id = ?", String.class, a))
                .isEqualTo("system:payroll-batch");
        assertThat(batch.calculatePendingRuns()).isZero();
    }

    @Test
    void salaryRecordsAreReadOnlyAndNeedPayrollView() throws Exception {
        JsonNode records = read(as(viewer, get("/api/v1/payroll/employees/1/salary-records"))
                .andExpect(status().isOk()));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("baseSalary").decimalValue()).isEqualByComparingTo("450000");
        assertThat(records.get(0).get("active").asBoolean()).isTrue();
        expect(as(nobody, get("/api/v1/payroll/employees/1/salary-records")), 403, "FORBIDDEN", null);
    }

    // ------------------------------------------------------------------ helpers

    private long monthlyPeriods(String name) throws Exception {
        send(admin, post("/api/v1/payroll/pay-periods/generate"), Map.of("year", 2026, "frequency", "MONTHLY"))
                .andExpect(status().isCreated());
        return periodId(name);
    }

    private long periodId(String name) {
        return jdbc.queryForObject("SELECT period_id FROM hrms.pay_periods WHERE period_name = ?", Long.class, name);
    }

    private long createAndCalculate(long periodId) throws Exception {
        long runId = read(send(admin, post("/api/v1/payroll/runs"), Map.of("periodId", periodId))
                .andExpect(status().isCreated())).get("runId").asLong();
        command(admin, runId, "calculate").andExpect(status().isOk());
        return runId;
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return as(token, request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions command(String token, long runId, String verb) throws Exception {
        return as(token, post("/api/v1/payroll/runs/" + runId + "/" + verb));
    }

    private ResultActions command(String token, long runId, String verb, Object body) throws Exception {
        return send(token, post("/api/v1/payroll/runs/" + runId + "/" + verb), body);
    }

    private static void expect(ResultActions r, int status, String code, Integer legacy) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        if (legacy == null) {
            r.andExpect(jsonPath("$.legacyCode").isEmpty());
        } else {
            r.andExpect(jsonPath("$.legacyCode").value(legacy));
        }
    }
}
