package com.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Employee slice over HTTP. Seed: James Richardson (1, grade 10), Patricia Williams (10, VP-HR grade 9),
 * Jennifer Park (21, grade 6) manages Thomas Baker (22, grade 4) and Andrew Patel (24, grade 2);
 * Brian Foster (99) is terminated. Clock is fixed at 2026-11-19.
 */
class EmployeeApiTest extends IntegrationTest {

    @Autowired
    RequestMappingHandlerMapping mappings;

    private String hr;
    private String thomas;
    private String andrew;
    private String jennifer;

    @BeforeEach
    void setUp() throws Exception {
        hr = login("patricia.williams@company.com");
        thomas = login("thomas.baker@company.com");
        andrew = login("andrew.patel@company.com");
        jennifer = login("jennifer.park@company.com");
    }

    private Map<String, Object> newHire(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "  ada ");
        body.put("lastName", " lovelace");
        body.put("email", email);
        body.put("hireDate", "2026-12-01");
        body.put("deptId", 20);
        body.put("jobId", 42);
        body.put("managerEmpId", 21);
        body.put("baseSalary", 95000);
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return read(callJson(post("/api/v1/employees"), hr, body).andExpect(status().isCreated()));
    }

    private void expectError(org.springframework.test.web.servlet.ResultActions r, int http, String code)
            throws Exception {
        r.andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void searchFiltersUseBindParametersAndPrefixMatching() throws Exception {
        JsonNode byName = read(call(get("/api/v1/employees").param("lastName", "bak"), andrew)
                .andExpect(status().isOk()));
        assertThat(byName).extracting(n -> n.get("empId").asLong()).contains(22L);
        JsonNode injection = read(call(get("/api/v1/employees").param("lastName", "%' OR '1'='1"), andrew)
                .andExpect(status().isOk()));
        assertThat(injection).isEmpty();
        JsonNode wildcard = read(call(get("/api/v1/employees").param("lastName", "%"), andrew)
                .andExpect(status().isOk()));
        assertThat(wildcard).isEmpty();
        JsonNode terminated = read(call(get("/api/v1/employees").param("status", "TERMINATED"), andrew)
                .andExpect(status().isOk()));
        assertThat(terminated).extracting(n -> n.get("empId").asLong()).containsExactly(99L);
        long chicago = jdbc.queryForObject("SELECT location_id FROM locations WHERE location_code = 'CHI'",
                Long.class);
        JsonNode inChicago = read(call(get("/api/v1/employees").param("locationId", String.valueOf(chicago))
                .param("deptId", "31").param("hiredFrom", "2019-01-01").param("hiredTo", "2020-12-31")
                .param("limit", "2"), andrew).andExpect(status().isOk()));
        assertThat(inChicago).hasSize(2).allSatisfy(n -> assertThat(n.get("locationCode").asText()).isEqualTo("CHI"));
        expectError(call(get("/api/v1/employees").param("status", "BOGUS"), andrew), 400, "-20000");
    }

    @Test
    void getEmployeeShowsSalaryOnlyToSelfManagerChainOrGradeFive() throws Exception {
        JsonNode self = read(call(get("/api/v1/employees/22"), thomas).andExpect(status().isOk()));
        assertThat(self.get("fullName").asText()).isEqualTo("THOMAS BAKER");
        assertThat(self.get("locationCode").asText()).isEqualTo("HQ");
        assertThat(self.get("locationId").asLong()).isPositive();
        assertThat(self.get("gradeLevel").asInt()).isEqualTo(4);
        assertThat(self.get("tenureYears").decimalValue()).isGreaterThan(BigDecimal.valueOf(8));
        assertThat(self.get("currentSalary").get("baseSalary").decimalValue()).isEqualByComparingTo("95000");
        JsonNode peer = read(call(get("/api/v1/employees/22"), andrew).andExpect(status().isOk()));
        assertThat(peer.has("currentSalary")).isFalse();
        JsonNode manager = read(call(get("/api/v1/employees/22"), jennifer).andExpect(status().isOk()));
        assertThat(manager.has("currentSalary")).isTrue();
        JsonNode byNumber = read(call(get("/api/v1/employees/by-number/emp-000022"), andrew)
                .andExpect(status().isOk()));
        assertThat(byNumber.get("empId").asLong()).isEqualTo(22);
        expectError(call(get("/api/v1/employees/999999"), andrew), 404, "-20001");
        expectError(call(get("/api/v1/employees/by-number/EMP-999999"), andrew), 404, "-20001");
    }

    @Test
    void createUsesSequenceNumbersNormalizesAndRecordsHistorySalaryAuditAndNotifications() throws Exception {
        JsonNode created = create(newHire(" Ada.Lovelace@Company.COM "));
        long id = created.get("empId").asLong();
        assertThat(created.get("empNumber").asText()).matches("EMP-\\d{6}");
        assertThat(Integer.parseInt(created.get("empNumber").asText().substring(4))).isGreaterThanOrEqualTo(1000);
        assertThat(created.get("firstName").asText()).isEqualTo("ADA");
        assertThat(created.get("lastName").asText()).isEqualTo("LOVELACE");
        assertThat(created.get("email").asText()).isEqualTo("ada.lovelace@company.com");
        assertThat(created.get("employmentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("locationCode").asText()).isEqualTo("HQ");
        assertThat(created.get("warnings")).isEmpty();
        assertThat(created.get("currentSalary").get("changeReason").asText()).isEqualTo("NEW_HIRE");
        JsonNode second = create(newHire("grace.hopper@company.com"));
        assertThat(second.get("empNumber").asText()).isNotEqualTo(created.get("empNumber").asText());
        assertThat(count("SELECT count(*) FROM employee_history WHERE emp_id = ? AND change_type = 'HIRE'", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE table_name = 'EMPLOYEES' AND record_id = ?"
                + " AND action_type = 'INSERT'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_queue WHERE recipient_emp_id IN (?, 21)"
                + " AND subject IN ('Welcome to the company', 'New direct report')", id)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void salaryOutsideGradeIsAWarningNotAnError() throws Exception {
        Map<String, Object> body = newHire("low.pay@company.com");
        body.put("baseSalary", 10000);
        JsonNode created = create(body);
        assertThat(created.get("warnings").get(0).asText()).contains("below minimum for grade G4");
        assertThat(created.get("currentSalary").get("baseSalary").decimalValue()).isEqualByComparingTo("10000");
    }

    @Test
    void createValidationErrorsKeepLegacyCodes() throws Exception {
        Map<String, Object> blank = newHire("blank@company.com");
        blank.put("lastName", "  ");
        expectError(callJson(post("/api/v1/employees"), hr, blank), 422, "-20010");
        Map<String, Object> farFuture = newHire("future@company.com");
        farFuture.put("hireDate", "2027-02-18");
        expectError(callJson(post("/api/v1/employees"), hr, farFuture), 422, "-20501");
        Map<String, Object> ninetyDays = newHire("ninety@company.com");
        ninetyDays.put("hireDate", "2027-02-17");
        create(ninetyDays);
        expectError(callJson(post("/api/v1/employees"), hr, newHire("THOMAS.BAKER@company.com")), 409, "-20502");
        Map<String, Object> dept = newHire("dept@company.com");
        dept.put("deptId", 9999);
        expectError(callJson(post("/api/v1/employees"), hr, dept), 422, "-20003");
        Map<String, Object> job = newHire("job@company.com");
        job.put("jobId", 9999);
        expectError(callJson(post("/api/v1/employees"), hr, job), 422, "-20011");
        Map<String, Object> manager = newHire("mgr@company.com");
        manager.put("managerEmpId", 99);
        expectError(callJson(post("/api/v1/employees"), hr, manager), 422, "-20004");
        Map<String, Object> temporary = newHire("temp@company.com");
        temporary.put("employmentType", "TEMPORARY");
        expectError(callJson(post("/api/v1/employees"), hr, temporary), 400, "-20000");
        Map<String, Object> badEmail = newHire("not-an-email");
        expectError(callJson(post("/api/v1/employees"), hr, badEmail), 400, "-20000");
        expectError(callJson(post("/api/v1/employees"), thomas, newHire("denied@company.com")), 403, "-20304");
    }

    @Test
    void duplicateEmployeeNumberMapsToLegacyCode() throws Exception {
        jdbc.queryForObject("SELECT setval('seq_emp_number', 21, false)", Long.class);
        expectError(callJson(post("/api/v1/employees"), hr, newHire("dupnum@company.com")), 409, "-20002");
    }

    @Test
    void employeeUpdatesOwnContactDetailsButNotOthers() throws Exception {
        Map<String, Object> patchBody = Map.of("phoneMobile", "212-555-9999", "city", "Brooklyn",
                "email", "Thomas.B@Company.com");
        JsonNode updated = read(callJson(patch("/api/v1/employees/22"), thomas, patchBody)
                .andExpect(status().isOk()));
        assertThat(updated.get("phoneMobile").asText()).isEqualTo("212-555-9999");
        assertThat(updated.get("city").asText()).isEqualTo("Brooklyn");
        assertThat(updated.get("email").asText()).isEqualTo("thomas.b@company.com");
        assertThat(updated.get("firstName").asText()).isEqualTo("THOMAS");
        expectError(callJson(patch("/api/v1/employees/24"), thomas, Map.of("city", "X")), 403, "-20304");
        expectError(callJson(patch("/api/v1/employees/22"), thomas, Map.of("email", "lisa.wong@company.com")),
                409, "-20502");
        expectError(callJson(patch("/api/v1/employees/22"), thomas, Map.of("lastName", " ")), 422, "-20010");
        expectError(callJson(patch("/api/v1/employees/999999"), hr, Map.of("city", "X")), 404, "-20001");
        callJson(patch("/api/v1/employees/24"), hr, Map.of("city", "Queens")).andExpect(status().isOk());
    }

    @Test
    void transferValidatesAndRecordsHistory() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("deptId", 30, "managerEmpId", 30,
                "effectiveDate", "2026-12-01", "reason", "Regional move"));
        long chicago = jdbc.queryForObject("SELECT location_id FROM locations WHERE location_code = 'CHI'",
                Long.class);
        body.put("locationId", chicago);
        JsonNode moved = read(callJson(post("/api/v1/employees/22/transfer"), hr, body).andExpect(status().isOk()));
        assertThat(moved.get("deptId").asLong()).isEqualTo(30);
        assertThat(moved.get("managerEmpId").asLong()).isEqualTo(30);
        assertThat(moved.get("locationCode").asText()).isEqualTo("CHI");
        assertThat(moved.get("jobId").asLong()).isEqualTo(42);
        JsonNode history = read(call(get("/api/v1/employees/22/history"), thomas).andExpect(status().isOk()));
        assertThat(history.get(0).get("changeType").asText()).isEqualTo("TRANSFER");
        assertThat(history.get(0).get("oldValues").get("deptId").asLong()).isEqualTo(20);
        assertThat(history.get(0).get("newValues").get("locationCode").asText()).isEqualTo("CHI");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Regional move");

        expectError(callJson(post("/api/v1/employees/99/transfer"), hr,
                Map.of("deptId", 30, "effectiveDate", "2026-12-01")), 409, "-20012");
        expectError(callJson(post("/api/v1/employees/21/transfer"), hr,
                Map.of("deptId", 20, "managerEmpId", 23, "effectiveDate", "2026-12-01")), 422, "-20004");
        expectError(callJson(post("/api/v1/employees/21/transfer"), hr,
                Map.of("deptId", 20, "managerEmpId", 21, "effectiveDate", "2026-12-01")), 422, "-20004");
        expectError(callJson(post("/api/v1/employees/23/transfer"), hr,
                Map.of("deptId", 9999, "effectiveDate", "2026-12-01")), 422, "-20003");
        expectError(callJson(post("/api/v1/employees/23/transfer"), hr,
                Map.of("deptId", 20, "jobId", 9999, "effectiveDate", "2026-12-01")), 422, "-20011");
        expectError(callJson(post("/api/v1/employees/23/transfer"), jennifer,
                Map.of("deptId", 30, "effectiveDate", "2026-12-01")), 403, "-20304");
        expectError(callJson(post("/api/v1/employees/23/transfer"), hr, Map.of("deptId", 30)), 400, "-20000");
    }

    @Test
    void promoteCreatesSalaryRecordThroughSharedServiceAndWarnsOutsideGrade() throws Exception {
        JsonNode promoted = read(callJson(post("/api/v1/employees/22/promote"), hr,
                Map.of("jobId", 32, "newSalary", 120000, "effectiveDate", "2026-12-01"))
                .andExpect(status().isOk()));
        assertThat(promoted.get("jobId").asLong()).isEqualTo(32);
        assertThat(promoted.get("gradeLevel").asInt()).isEqualTo(6);
        assertThat(promoted.get("warnings")).isEmpty();
        JsonNode salaries = read(call(get("/api/v1/employees/22/salary-history"), thomas)
                .andExpect(status().isOk()));
        assertThat(salaries.get(0).get("baseSalary").decimalValue()).isEqualByComparingTo("120000");
        assertThat(salaries.get(0).get("changeReason").asText()).isEqualTo("PROMOTION");
        assertThat(salaries.get(1).get("endDate").asText()).isEqualTo("2026-11-30");
        assertThat(count("SELECT count(*) FROM employee_history WHERE emp_id = 22 AND change_type = 'PROMOTION'"
                + " AND old_salary = 95000 AND new_salary = 120000")).isEqualTo(1);
        JsonNode warned = read(callJson(post("/api/v1/employees/23/promote"), hr,
                Map.of("jobId", 32, "newSalary", 50000, "effectiveDate", "2026-12-01"))
                .andExpect(status().isOk()));
        assertThat(warned.get("warnings").get(0).asText()).contains("below minimum");
        expectError(callJson(post("/api/v1/employees/22/promote"), hr,
                Map.of("jobId", 9999, "newSalary", 1, "effectiveDate", "2026-12-01")), 422, "-20011");
        expectError(callJson(post("/api/v1/employees/22/promote"), thomas,
                Map.of("jobId", 32, "newSalary", 1, "effectiveDate", "2026-12-01")), 403, "-20304");
    }

    @Test
    void terminateCancelsPendingLeaveReleasesBalanceAndRehireIsAllowed() throws Exception {
        Map<String, Object> leave = Map.of("leaveTypeId", 1, "startDate", "2026-12-07", "endDate",
                "2026-12-11", "reason", "holiday");
        JsonNode pending = read(callJson(post("/api/v1/leave/requests"), thomas, leave)
                .andExpect(status().isCreated()));
        String balanceSql = "SELECT pending FROM leave_balances WHERE emp_id = 22 AND leave_type_id = 1"
                + " AND calendar_year = 2026";
        assertThat(jdbc.queryForObject(balanceSql, BigDecimal.class)).isEqualByComparingTo("5");

        JsonNode terminated = read(callJson(post("/api/v1/employees/22/terminate"), hr,
                Map.of("terminationDate", "2026-11-30", "reason", "VOLUNTARY")).andExpect(status().isOk()));
        assertThat(terminated.get("employmentStatus").asText()).isEqualTo("TERMINATED");
        assertThat(terminated.get("active").asBoolean()).isFalse();
        assertThat(terminated.get("terminationDate").asText()).isEqualTo("2026-11-30");
        assertThat(jdbc.queryForObject("SELECT status FROM leave_requests WHERE request_id = ?", String.class,
                pending.get("requestId").asLong())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(balanceSql, BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM employees WHERE emp_id = 22")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_queue WHERE recipient_emp_id = 21"
                + " AND subject = 'Employee termination'")).isEqualTo(1);
        expectError(callJson(post("/api/v1/employees/22/terminate"), hr,
                Map.of("terminationDate", "2026-12-01", "reason", "AGAIN")), 409, "-20005");
        expectError(callJson(post("/api/v1/employees/22/transfer"), hr,
                Map.of("deptId", 30, "effectiveDate", "2026-12-01")), 409, "-20012");

        expectError(callJson(post("/api/v1/employees/22/rehire"), hr,
                Map.of("hireDate", "2027-03-01", "deptId", 20, "jobId", 42)), 422, "-20501");
        JsonNode rehired = read(callJson(post("/api/v1/employees/22/rehire"), hr,
                Map.of("hireDate", "2026-12-15", "deptId", 20, "jobId", 42, "managerEmpId", 21))
                .andExpect(status().isOk()));
        assertThat(rehired.get("employmentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(rehired.get("active").asBoolean()).isTrue();
        assertThat(rehired.get("hireDate").asText()).isEqualTo("2026-12-15");
        assertThat(rehired.has("terminationDate")).isFalse();
        assertThat(rehired.get("currentSalary").get("changeReason").asText()).isEqualTo("REHIRE");
        assertThat(rehired.get("currentSalary").get("baseSalary").decimalValue()).isEqualByComparingTo("95000");
        assertThat(count("SELECT count(*) FROM employee_history WHERE emp_id = 22 AND change_type IN"
                + " ('TERMINATION', 'REHIRE')")).isEqualTo(2);
        expectError(callJson(post("/api/v1/employees/22/rehire"), hr,
                Map.of("hireDate", "2026-12-15", "deptId", 20, "jobId", 42)), 409, "-20991");
        expectError(callJson(post("/api/v1/employees/23/terminate"), jennifer,
                Map.of("terminationDate", "2026-12-01", "reason", "X")), 403, "-20304");
    }

    @Test
    void rehireRejectsEmailNowUsedByAnotherActiveEmployee() throws Exception {
        create(newHire("brian.foster@company.com"));
        expectError(callJson(post("/api/v1/employees/99/rehire"), hr,
                Map.of("hireDate", "2026-12-01", "deptId", 31, "jobId", 50)), 409, "-20502");
    }

    @Test
    void hierarchyEndpoints() throws Exception {
        JsonNode reports = read(call(get("/api/v1/employees/21/direct-reports"), andrew).andExpect(status().isOk()));
        assertThat(reports).extracting(n -> n.get("empId").asLong()).contains(22L, 23L, 24L);
        JsonNode chart = read(call(get("/api/v1/employees/1/org-chart"), andrew).andExpect(status().isOk()));
        Map<Long, Integer> levels = new HashMap<>();
        chart.forEach(n -> levels.put(n.get("empId").asLong(), n.get("level").asInt()));
        assertThat(levels.get(2L)).isEqualTo(1);
        assertThat(levels.get(22L)).isEqualTo(levels.get(21L) + 1);
        assertThat(levels).doesNotContainKey(99L);
        expectError(call(get("/api/v1/employees/999999/org-chart"), andrew), 404, "-20001");
    }

    @Test
    void historyHidesSalaryAndSalaryHistoryIsRestricted() throws Exception {
        callJson(post("/api/v1/employees/22/promote"), hr,
                Map.of("jobId", 32, "newSalary", 120000, "effectiveDate", "2026-12-01")).andExpect(status().isOk());
        JsonNode peerView = read(call(get("/api/v1/employees/22/history"), andrew).andExpect(status().isOk()));
        assertThat(peerView.get(0).get("newValues").has("salary")).isFalse();
        assertThat(peerView.get(0).get("newValues").get("jobId").asLong()).isEqualTo(32);
        JsonNode managerView = read(call(get("/api/v1/employees/22/history"), jennifer).andExpect(status().isOk()));
        assertThat(managerView.get(0).get("newValues").get("salary").decimalValue()).isEqualByComparingTo("120000");
        expectError(call(get("/api/v1/employees/22/salary-history"), andrew), 403, "-20304");
        call(get("/api/v1/employees/22/salary-history"), jennifer).andExpect(status().isOk());
        call(get("/api/v1/employees/22/salary-history"), hr).andExpect(status().isOk());
    }

    @Test
    void dependentsCrudWithSoftDelete() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("firstName", "mia", "lastName", "baker",
                "relationship", "child", "dateOfBirth", "2015-04-01", "gender", "F", "benefitsEligible", true));
        JsonNode added = read(callJson(post("/api/v1/employees/22/dependents"), thomas, body)
                .andExpect(status().isCreated()));
        long id = added.get("dependentId").asLong();
        assertThat(added.get("firstName").asText()).isEqualTo("MIA");
        assertThat(added.get("relationship").asText()).isEqualTo("CHILD");
        assertThat(added.get("benefitsEligible").asBoolean()).isTrue();
        body.put("relationship", "OTHER");
        body.put("benefitsEligible", false);
        JsonNode changed = read(callJson(put("/api/v1/employees/22/dependents/" + id), thomas, body)
                .andExpect(status().isOk()));
        assertThat(changed.get("relationship").asText()).isEqualTo("OTHER");
        assertThat(changed.get("benefitsEligible").asBoolean()).isFalse();
        JsonNode list = read(call(get("/api/v1/employees/22/dependents"), jennifer).andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        expectError(call(get("/api/v1/employees/22/dependents"), andrew), 403, "-20304");
        expectError(callJson(post("/api/v1/employees/22/dependents"), andrew, body), 403, "-20304");
        body.put("relationship", "COUSIN");
        expectError(callJson(post("/api/v1/employees/22/dependents"), thomas, body), 400, "-20000");
        call(delete("/api/v1/employees/22/dependents/" + id), thomas).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/employees/22/dependents"), thomas))).isEmpty();
        assertThat(count("SELECT count(*) FROM employee_dependents WHERE dependent_id = ? AND active_flag = 'N'",
                id)).isEqualTo(1);
        expectError(call(delete("/api/v1/employees/22/dependents/" + id), thomas), 404, "-20990");
        expectError(callJson(put("/api/v1/employees/24/dependents/" + id), hr, body), 404, "-20990");
    }

    @Test
    void emergencyContactsCrudKeepsOnePrimary() throws Exception {
        JsonNode first = read(callJson(post("/api/v1/employees/22/emergency-contacts"), thomas,
                Map.of("contactName", "Anna Baker", "relationship", "Spouse", "phonePrimary", "212-555-0001"))
                .andExpect(status().isCreated()));
        assertThat(first.get("isPrimary").asBoolean()).isTrue();
        JsonNode second = read(callJson(post("/api/v1/employees/22/emergency-contacts"), hr,
                Map.of("contactName", "Bob Baker", "phonePrimary", "212-555-0002", "email", "Bob@Example.com",
                        "isPrimary", true)).andExpect(status().isCreated()));
        assertThat(second.get("email").asText()).isEqualTo("bob@example.com");
        List<Boolean> primaries = new ArrayList<>();
        read(call(get("/api/v1/employees/22/emergency-contacts"), thomas)).forEach(
                n -> primaries.add(n.get("isPrimary").asBoolean()));
        assertThat(primaries).containsExactly(true, false);
        long firstId = first.get("contactId").asLong();
        JsonNode updated = read(callJson(put("/api/v1/employees/22/emergency-contacts/" + firstId), thomas,
                Map.of("contactName", "Anna B", "phonePrimary", "212-555-0003", "isPrimary", true))
                .andExpect(status().isOk()));
        assertThat(updated.get("contactName").asText()).isEqualTo("Anna B");
        expectError(callJson(post("/api/v1/employees/22/emergency-contacts"), thomas,
                Map.of("contactName", "Bad", "phonePrimary", "abc")), 400, "-20000");
        expectError(call(get("/api/v1/employees/22/emergency-contacts"), andrew), 403, "-20304");
        expectError(call(delete("/api/v1/employees/22/emergency-contacts/" + firstId), andrew), 403, "-20304");
        call(delete("/api/v1/employees/22/emergency-contacts/" + firstId), thomas).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/employees/22/emergency-contacts"), thomas))).hasSize(1);
        expectError(call(delete("/api/v1/employees/22/emergency-contacts/999999"), thomas), 404, "-20990");
    }

    @Test
    void referenceDataAndHeadcount() throws Exception {
        JsonNode depts = read(call(get("/api/v1/departments"), andrew).andExpect(status().isOk()));
        assertThat(depts).anySatisfy(d -> assertThat(d.get("code").asText()).isNotBlank());
        JsonNode locations = read(call(get("/api/v1/locations"), andrew).andExpect(status().isOk()));
        assertThat(locations).extracting(n -> n.get("code").asText()).contains("HQ", "CHI", "SF");
        assertThat(locations).allSatisfy(n -> assertThat(n.get("id").asLong()).isPositive());
        JsonNode jobs = read(call(get("/api/v1/job-titles"), andrew).andExpect(status().isOk()));
        assertThat(jobs).anySatisfy(j -> {
            assertThat(j.get("id").asLong()).isEqualTo(42);
            assertThat(j.get("gradeId").asInt()).isEqualTo(4);
        });
        int it = count("SELECT count(*) FROM employees WHERE dept_id = 31 AND employment_status = 'ACTIVE'");
        JsonNode now = read(call(get("/api/v1/departments/headcount"), andrew).andExpect(status().isOk()));
        assertThat(now).anySatisfy(r -> {
            assertThat(r.get("deptId").asLong()).isEqualTo(31);
            assertThat(r.get("headcount").asInt()).isEqualTo(it);
        });
        JsonNode past = read(call(get("/api/v1/departments/headcount").param("asOf", "2023-01-01"), andrew)
                .andExpect(status().isOk()));
        assertThat(past).anySatisfy(r -> {
            assertThat(r.get("deptId").asLong()).isEqualTo(31);
            assertThat(r.get("headcount").asInt()).isEqualTo(it + 1);
        });
    }

    @Test
    void employeesCannotBeHardDeleted() throws Exception {
        boolean deleteRoute = mappings.getHandlerMethods().keySet().stream().anyMatch(info ->
                info.getPatternValues().contains("/api/v1/employees/{empId}")
                        && info.getMethodsCondition().getMethods().contains(RequestMethod.DELETE));
        assertThat(deleteRoute).isFalse();
        int status = call(delete("/api/v1/employees/22"), hr).andReturn().getResponse().getStatus();
        assertThat(status).isGreaterThanOrEqualTo(400);
        assertThat(count("SELECT count(*) FROM employees WHERE emp_id = 22")).isEqualTo(1);
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/employees")).andExpect(status().isUnauthorized());
    }
}
