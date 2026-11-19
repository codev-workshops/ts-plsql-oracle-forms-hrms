package com.hrms.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportingApiTest extends IntegrationTest {

    private static final String BASE = "/api/v1/reports/";
    private static final String MANAGER = "jennifer.park@company.com";
    private static final MediaType CSV = MediaType.valueOf("text/csv");
    private static final List<String> ROUTES = List.of("headcount", "compensation", "turnover",
            "new-hires", "leave-utilization", "payroll-summary", "eeo");

    private String token;

    @BeforeEach
    void setup() throws Exception {
        token = login(MANAGER);
        jdbc.update("INSERT INTO departments (dept_id, dept_code, dept_name, created_by) "
                + "VALUES (901, 'RPT', 'Reporting QA', 'test'), (902, 'RPT-OTHER', 'Other QA', 'test')");
        jdbc.update("""
                INSERT INTO job_titles (job_id, job_code, job_title, grade_id, eeo_category, created_by)
                VALUES (901, 'RPT-JOB', 'Reporting QA Job', 3, 'RPT', 'test')
                """);
    }

    @Test
    void headcountHonorsLocationIdDepartmentAsOfAndPreservesColumnsWhenEmpty() throws Exception {
        employee(901, 901, "HQ", "2024-11-19", "ACTIVE", "FULL_TIME", "F");
        employee(902, 901, "HQ", "2024-11-19", "ACTIVE", "PART_TIME", "M");
        employee(903, 901, "HQ", "2026-11-20", "ACTIVE", "FULL_TIME", "M");
        employee(904, 901, "HQ", "2024-11-19", "TERMINATED", "CONTRACT", "O");
        employee(905, 901, "CHI", "2024-11-19", "ACTIVE", "CONTRACT", "O");
        employee(906, 902, "HQ", "2024-11-19", "ACTIVE", "FULL_TIME", "F");
        employee(907, 901, "HQ", "2024-11-19", "ACTIVE", "CONTRACT", "O");
        terminateDate(907, "2026-11-19", "VOLUNTARY");

        var result = report(get(BASE + "headcount").param("deptId", "901").param("locationId", "2"));
        result.andExpect(jsonPath("$.name").value("Headcount"))
                .andExpect(jsonPath("$.generatedAt").value(NOW.toString() + ":00"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].location_name").value("Corporate Headquarters"))
                .andExpect(jsonPath("$.rows[0].headcount").value(2))
                .andExpect(jsonPath("$.rows[0].ft_count").value(1))
                .andExpect(jsonPath("$.rows[0].pt_count").value(1))
                .andExpect(jsonPath("$.rows[0].contract_count").value(0))
                .andExpect(jsonPath("$.rows[0].female_count").value(1))
                .andExpect(jsonPath("$.rows[0].male_count").value(1))
                .andExpect(jsonPath("$.rows[0].avg_tenure_years").value(2.0));
        report(get(BASE + "headcount").param("deptId", "901").param("locationId", "1"))
                .andExpect(jsonPath("$.rows[0].contract_count").value(1));
        report(get(BASE + "headcount").param("deptId", "901"))
                .andExpect(jsonPath("$.rows.length()").value(2));

        for (MockHttpServletRequestBuilder request : List.of(
                get(BASE + "headcount").param("locationId", "9999"),
                get(BASE + "headcount").param("deptId", "901").param("asOf", "2024-11-18"))) {
            JsonNode empty = read(report(request).andExpect(jsonPath("$.rows").isEmpty()));
            assertThat(empty.get("columns")).isEqualTo(read(result).get("columns"));
        }
    }

    @Test
    void headcountFindsNewLocationsAndUsesCalendarMonthsForTenure() throws Exception {
        Long id = jdbc.queryForObject("""
                INSERT INTO locations (location_code, location_name, created_by)
                VALUES ('RPT-NEW', 'Reporting QA Location', 'test') RETURNING location_id
                """, Long.class);
        employee(901, 901, "RPT-NEW", "2026-07-14", "ACTIVE", "FULL_TIME", "F");
        report(get(BASE + "headcount").param("locationId", id.toString()))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].location_name").value("Reporting QA Location"))
                .andExpect(jsonPath("$.rows[0].avg_tenure_years").value(0.3));
    }

    @Test
    void compensationAggregatesActiveSalariesAndComputesMedian() throws Exception {
        employee(901, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "F");
        employee(902, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "M");
        employee(903, 901, "HQ", "2024-01-01", "TERMINATED", "FULL_TIME", "O");
        salary(901, 60000, "Y");
        salary(901, 10000, "N");
        salary(902, 80000, "Y");
        salary(903, 110000, "Y");
        jdbc.update("UPDATE job_grades SET min_salary = 50000, max_salary = 100000 WHERE grade_id = 3");

        report(get(BASE + "compensation").param("deptId", "901").param("gradeId", "3"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].emp_count").value(2))
                .andExpect(jsonPath("$.rows[0].grade_min").value(50000))
                .andExpect(jsonPath("$.rows[0].grade_max").value(100000))
                .andExpect(jsonPath("$.rows[0].actual_min").value(60000))
                .andExpect(jsonPath("$.rows[0].actual_max").value(80000))
                .andExpect(jsonPath("$.rows[0].avg_salary").value(70000))
                .andExpect(jsonPath("$.rows[0].median_salary").value(70000))
                .andExpect(jsonPath("$.rows[0].compa_ratio").value(93.3));
        report(get(BASE + "compensation").param("deptId", "901").param("gradeId", "4"))
                .andExpect(jsonPath("$.rows").isEmpty());
    }

    @Test
    void turnoverUsesInclusiveDatesAndCountsOnlyEmployeesHiredByEndDate() throws Exception {
        employee(901, 901, "HQ", "2024-01-01", "TERMINATED", "FULL_TIME", "F");
        employee(902, 901, "HQ", "2024-01-31", "TERMINATED", "FULL_TIME", "M");
        employee(903, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "M");
        employee(904, 901, "HQ", "2026-02-01", "ACTIVE", "FULL_TIME", "M");
        terminateDate(901, "2026-01-01", "VOLUNTARY");
        terminateDate(902, "2026-01-31", "INVOLUNTARY");

        report(get(BASE + "turnover").param("deptId", "901")
                .param("startDate", "2026-01-01").param("endDate", "2026-01-31"))
                .andExpect(jsonPath("$.rows[0].terminations").value(2))
                .andExpect(jsonPath("$.rows[0].current_hc").value(1))
                .andExpect(jsonPath("$.rows[0].turnover_pct").value(66.7))
                .andExpect(jsonPath("$.rows[0].voluntary").value(1))
                .andExpect(jsonPath("$.rows[0].involuntary").value(1))
                .andExpect(jsonPath("$.rows[0].avg_tenure_at_exit").value(2.0));
    }

    @Test
    void newHiresIncludeEmployeesWithoutManagerOrSalaryAndSortNewestFirst() throws Exception {
        employee(901, 901, null, "2026-01-01", "ACTIVE", "FULL_TIME", "F");
        employee(902, 901, "SF", "2026-01-31", "ACTIVE", "INTERN", "M");
        employee(903, 901, "HQ", "2025-12-31", "ACTIVE", "FULL_TIME", "M");
        employee(904, 902, "HQ", "2026-01-31", "ACTIVE", "FULL_TIME", "M");
        salary(902, 60000, "Y");
        jdbc.update("UPDATE employees SET manager_emp_id = 1 WHERE emp_id = 902");

        var rows = read(report(get(BASE + "new-hires").param("deptId", "901")
                .param("startDate", "2026-01-01").param("endDate", "2026-01-31"))).get("rows");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).get("hire_date").asText()).isEqualTo("2026-01-31");
        assertThat(rows.get(0).get("manager_name").asText()).isEqualTo("JAMES RICHARDSON");
        assertThat(rows.get(0).get("base_salary").asInt()).isEqualTo(60000);
        assertThat(rows.get(1).hasNonNull("location_name")).isFalse();
        assertThat(rows.get(1).hasNonNull("manager_name")).isFalse();
        assertThat(rows.get(1).hasNonNull("base_salary")).isFalse();
    }

    @Test
    void leaveShowsPendingSeparatelyAndHandlesZeroEntitlement() throws Exception {
        employee(901, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "F");
        employee(902, 901, "HQ", "2024-01-01", "TERMINATED", "FULL_TIME", "F");
        jdbc.update("""
                INSERT INTO leave_balances (emp_id, leave_type_id, calendar_year, opening_balance,
                    accrued, used, adjustment, pending, created_by)
                VALUES (901, 1, 2026, 8, 2, 4, 1, 2, 'test'),
                       (901, 2, 2026, 0, 0, 0, 0, 0, 'test'),
                       (901, 1, 2025, 40, 0, 0, 0, 0, 'test'),
                       (902, 1, 2026, 40, 0, 0, 0, 0, 'test')
                """);
        JsonNode rows = read(report(get(BASE + "leave-utilization").param("year", "2026")
                .param("deptId", "901"))).get("rows");
        assertThat(rows.size()).isEqualTo(2);
        for (JsonNode row : rows) {
            assertThat(row.get("emp_count").asInt()).isEqualTo(1);
            if (row.get("avg_entitled").asInt() == 0) {
                assertThat(row.hasNonNull("utilization_pct")).isFalse();
            } else {
                assertThat(row.get("avg_entitled").asInt()).isEqualTo(10);
                assertThat(row.get("avg_used").asInt()).isEqualTo(4);
                assertThat(row.get("avg_available").asInt()).isEqualTo(5);
                assertThat(row.get("avg_pending").asInt()).isEqualTo(2);
                assertThat(row.get("utilization_pct").asInt()).isEqualTo(40);
            }
        }
    }

    @Test
    void payrollBucketsTaxesCombinesDeductionsAndExcludesErrorRowsAndOtherPeriods() throws Exception {
        jdbc.update("""
                INSERT INTO pay_periods (period_id, period_name, pay_frequency, period_start_date,
                    period_end_date, pay_date, created_by)
                VALUES (901, 'Nov', 'MONTHLY', '2026-11-01', '2026-11-30', '2026-11-30', 'test'),
                       (902, 'Dec', 'MONTHLY', '2026-12-01', '2026-12-31', '2026-12-31', 'test')
                """);
        jdbc.update("""
                INSERT INTO payroll_runs (run_id, period_id, run_date, status, created_by)
                VALUES (901, 901, now(), 'APPROVED', 'test'), (902, 902, now(), 'APPROVED', 'test')
                """);
        jdbc.update("""
                INSERT INTO payroll_details (run_id, emp_id, element_id, element_type, amount, status, created_by)
                VALUES (901, 1, 1, 'EARNING', 1000, 'CALCULATED', 'test'),
                       (901, 1, 100, 'TAX', -100, 'CALCULATED', 'test'),
                       (901, 1, 101, 'TAX', -55, 'CALCULATED', 'test'),
                       (901, 1, 102, 'TAX', -25, 'CALCULATED', 'test'),
                       (901, 1, 103, 'TAX', -10, 'CALCULATED', 'test'),
                       (901, 1, 200, 'DEDUCTION', -40, 'CALCULATED', 'test'),
                       (901, 1, 201, 'BENEFIT', -20, 'CALCULATED', 'test'),
                       (901, 2, NULL, 'EARNING', 999, 'ERROR', 'test'),
                       (902, 2, 1, 'EARNING', 123, 'CALCULATED', 'test')
                """);
        report(get(BASE + "payroll-summary").param("periodId", "901"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].emp_count").value(1))
                .andExpect(jsonPath("$.rows[0].total_gross").value(1000))
                .andExpect(jsonPath("$.rows[0].total_fed_tax").value(100))
                .andExpect(jsonPath("$.rows[0].total_state_tax").value(55))
                .andExpect(jsonPath("$.rows[0].total_ss").value(25))
                .andExpect(jsonPath("$.rows[0].total_medicare").value(10))
                .andExpect(jsonPath("$.rows[0].total_deductions").value(250))
                .andExpect(jsonPath("$.rows[0].total_net").value(750));
    }

    @Test
    void eeoCountsEachGenderAndOnlyActiveEmployeesHiredByAsOfDate() throws Exception {
        employee(901, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "F");
        employee(902, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "M");
        employee(903, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", "O");
        employee(904, 901, "HQ", "2024-01-01", "ACTIVE", "FULL_TIME", null);
        employee(905, 901, "HQ", "2024-01-01", "TERMINATED", "FULL_TIME", "F");
        employee(906, 901, "HQ", "2026-11-19", "ACTIVE", "FULL_TIME", "F");
        var rows = read(report(get(BASE + "eeo").param("asOf", "2026-11-18"))).get("rows");
        JsonNode eeo = rows.get(rows.size() - 1);
        assertThat(eeo.get("eeo_category").asText()).isEqualTo("RPT");
        assertThat(eeo.get("total").asInt()).isEqualTo(4);
        for (String column : List.of("male", "female", "other_gender", "not_disclosed")) {
            assertThat(eeo.get(column).asInt()).isEqualTo(1);
        }
        assertThat(eeo.get("female_pct").asInt()).isEqualTo(25);
    }

    @Test
    void everyRouteNegotiatesCsvUsingJsonColumnOrderEvenWhenEmpty() throws Exception {
        for (String route : ROUTES) {
            JsonNode columns = read(report(request(route))).get("columns");
            var csv = call(request(route).accept(CSV), token)
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(CSV))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + route + ".csv\""))
                    .andExpect(header().string("Vary", "Accept"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(List.of(csv.split("\r\n", 2)[0].split(",")))
                    .containsExactlyElementsOf(java.util.stream.StreamSupport.stream(columns.spliterator(), false)
                            .map(JsonNode::asText).toList());
        }
        call(request("headcount").accept("text/csv;q=0.2, application/json"), token)
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void requiresReportingPermissionOnEveryRouteAndValidatesDateRanges() throws Exception {
        String employeeToken = login("thomas.baker@company.com");
        for (String route : ROUTES) {
            call(request(route), employeeToken).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("-20304"));
        }
        call(request("headcount").accept(CSV), employeeToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("-20304"));
        mvc.perform(get(BASE + "headcount")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("-20303"));
        for (String route : List.of("turnover", "new-hires")) {
            call(get(BASE + route).param("startDate", "2026-02-01").param("endDate", "2026-01-01"), token)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
            call(get(BASE + route), token)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
        }
        call(get(BASE + "headcount").param("locationId", "HQ"), token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
        call(get(BASE + "payroll-summary"), token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
        call(get(BASE + "leave-utilization"), token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("-20000"));
    }

    private ResultActions report(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, token).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    private static MockHttpServletRequestBuilder request(String route) {
        var request = get(BASE + route);
        return switch (route) {
            case "turnover", "new-hires" -> request.param("startDate", "2026-01-01").param("endDate", "2026-11-19");
            case "leave-utilization" -> request.param("year", "2026");
            case "payroll-summary" -> request.param("periodId", "901");
            default -> request;
        };
    }

    private void employee(long id, int deptId, String location, String hire, String status, String type, String gender) {
        jdbc.update("""
                INSERT INTO employees (emp_id, emp_number, first_name, last_name, hire_date, dept_id, job_id,
                                       location_code, employment_type, employment_status, gender, created_by)
                VALUES (?, ?, 'Report', 'Employee', ?, ?, 901, ?, ?, ?, ?, 'test')
                """, id, "RPT-" + id, LocalDate.parse(hire), deptId, location, type, status, gender);
    }

    private void terminateDate(long empId, String date, String reason) {
        jdbc.update("UPDATE employees SET termination_date = ?, termination_reason = ? WHERE emp_id = ?",
                LocalDate.parse(date), reason, empId);
    }

    private void salary(long empId, int amount, String active) {
        jdbc.update("""
                INSERT INTO salary_records (emp_id, effective_date, base_salary, active_flag, created_by)
                VALUES (?, '2024-01-01', ?, ?, 'test')
                """, empId, amount, active);
    }
}
