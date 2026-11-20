package com.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.auth.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P5 employee core. "Today" is 2026-03-02 (UTC). Emp 1 has every permission (grade >= 8); emp 37 (grade 2) has
 * only EMPLOYEE:VIEW. Each test works on its own seeded employees, because the class shares one database.
 */
class EmployeeCommandApiTest extends ApiTestSupport {

    String admin;
    String employee;

    @BeforeEach
    void signIn() throws Exception {
        clock.set(Instant.parse("2026-03-02T09:00:00Z"));
        admin = tokenFor(1);
        employee = tokenFor(37);
    }

    @Test
    void createHiresWithSequenceNumberHistoryAuditNotificationsAndThePort() throws Exception {
        JsonNode m = read(send(admin, post("/api/v1/employees"), hire("  Nina ", "Lopez", "Nina.Lopez@Company.com",
                31L, 30000)).andExpect(status().isCreated()));
        JsonNode e = m.get("employee");
        long id = e.get("empId").asLong();
        assertThat(e.get("empNumber").asText()).matches("^EMP-\\d{6}$");
        assertThat(e.get("firstName").asText()).isEqualTo("NINA");
        assertThat(e.get("email").asText()).isEqualTo("nina.lopez@company.com");
        assertThat(e.get("locationCode").asText()).isEqualTo("CHI"); // dept 31's location
        assertThat(e.get("employmentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(e.get("active").asBoolean()).isTrue();
        assertThat(m.get("warnings").get(0).get("code").asText()).isEqualTo("SALARY_OUTSIDE_GRADE");
        assertThat(m.get("warnings").get(0).get("message").asText())
                .isEqualTo("Salary $30,000.00 is below minimum for grade Junior ($45,000.00)");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM hrms.employees WHERE emp_id = ?", id);
        assertThat(row).containsEntry("created_by", "james.richardson@company.com");
        Map<String, Object> h = jdbc.queryForMap("SELECT * FROM hrms.employee_history WHERE emp_id = ?", id);
        assertThat(h).containsEntry("change_type", "HIRE").containsEntry("new_dept_id", 31L)
                .containsEntry("new_manager_id", 31L).containsEntry("new_location", "CHI");
        assertThat(jdbc.queryForObject("SELECT new_values->>'empNumber' FROM hrms.audit_log WHERE table_name = "
                + "'EMPLOYEES' AND record_id = ? AND action_type = 'INSERT'", String.class, id))
                .isEqualTo(e.get("empNumber").asText());
        List<Map<String, Object>> mail = jdbc.queryForList("SELECT recipient_emp_id, subject, body FROM "
                + "hrms.notification_queue WHERE reference_table = 'EMPLOYEES' AND reference_id = ? "
                + "ORDER BY notification_id", id);
        assertThat(mail).hasSize(2);
        assertThat(mail.get(0)).containsEntry("recipient_emp_id", id).containsEntry("subject", "Welcome to the Company");
        assertThat((String) mail.get(0).get("body")).endsWith("Your employee number is " + e.get("empNumber").asText() + ".");
        assertThat(mail.get(1)).containsEntry("recipient_emp_id", 31L)
                .containsEntry("subject", "New Direct Report: Nina Lopez")
                .containsEntry("body", "Nina Lopez has been added as your direct report, starting 03/16/2026.");
        // Salary through the port, implemented by payroll (P6, PKG-P01)
        assertThat(salaries(id)).singleElement().satisfies(r -> assertSalary(r, "2026-03-16", null, "30000",
                "NEW_HIRE", true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.audit_log WHERE table_name = 'SALARY_RECORDS' "
                + "AND action_type = 'INSERT' AND (new_values->>'empId')::bigint = ?", Long.class, id)).isEqualTo(1);
        // A second hire gets the next number from the sequence (D-02)
        JsonNode next = read(send(admin, post("/api/v1/employees"), hire("Omar", "Reyes", null, null, 50000))
                .andExpect(status().isCreated())).get("employee");
        assertThat(Long.parseLong(next.get("empNumber").asText().substring(4)))
                .isEqualTo(Long.parseLong(e.get("empNumber").asText().substring(4)) + 1);
        assertThat(read(send(admin, post("/api/v1/employees"), hire("Pia", "Kim", null, null, 50000))).get("warnings"))
                .isEmpty();
    }

    @Test
    void createValidationsKeepTheLegacyOrderAndCodes() throws Exception {
        Map<String, Object> b = hire("", "Lopez", null, null, 50000);
        expect(send(admin, post("/api/v1/employees"), b), 400, "VALIDATION_FAILED", -20010);
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", "bad@x", null, 1), "deptId", 999)), 400,
                "VALIDATION_FAILED", null); // field formats come first
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", null, 99L, 1), "deptId", 999)), 422,
                "INVALID_DEPARTMENT", -20003);
        expect(send(admin, post("/api/v1/employees"), hire("A", "B", null, inactive("TERMINATED"), 50000)), 422,
                "INVALID_MANAGER", -20004);
        expect(send(admin, post("/api/v1/employees"), hire("A", "B", null, 424242L, 50000)), 422,
                "INVALID_MANAGER", -20004);
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", null, null, 50000), "jobId", 999)), 422,
                "INVALID_JOB", -20011);
        expect(send(admin, post("/api/v1/employees"), hire("A", "B", null, null, 0)), 422, "INVALID_SALARY", -20101);
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", null, null, 50000), "hireDate",
                "2026-08-30")), 422, "HIRE_DATE_TOO_FAR", -20501); // today + 181
        send(admin, post("/api/v1/employees"), with(hire("A", "B", null, null, 50000), "hireDate", "2026-08-29"))
                .andExpect(status().isCreated()); // today + 180 (OQ-10)
        expect(send(admin, post("/api/v1/employees"), hire("A", "B", "ALEX.TAYLOR@company.com", null, 50000)), 409,
                "EMAIL_IN_USE", -20502);
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", null, null, 50000), "phoneWork", "555-1")),
                400, "VALIDATION_FAILED", null);
        expect(send(admin, post("/api/v1/employees"), with(hire("A", "B", null, null, 50000), "dateOfBirth",
                "2026-03-03")), 400, "VALIDATION_FAILED", null);
        expect(send(admin, post("/api/v1/employees"), Map.of("firstName", "A", "lastName", "B")), 400,
                "VALIDATION_FAILED", null);
        // A terminated employee's email is free again (uniqueness is among active rows only)
        long gone = inactive("TERMINATED");
        send(admin, post("/api/v1/employees"), hire("Again", "Two", "gone" + gone + "@company.com", null, 50000))
                .andExpect(status().isCreated());
    }

    @Test
    void writesNeedEmployeeEditAndDeleteIsAlwaysForbidden() throws Exception {
        expect(send(employee, post("/api/v1/employees"), hire("A", "B", null, null, 50000)), 403, "FORBIDDEN", null);
        expect(send(employee, patch("/api/v1/employees/37"), Map.of("city", "X")), 403, "FORBIDDEN", null);
        expect(send(employee, post("/api/v1/employees/37/terminate"), Map.of("terminationDate", "2026-03-02",
                "reason", "X")), 403, "FORBIDDEN", null);
        expect(as(employee, get("/api/v1/employees/37/history")), 403, "FORBIDDEN", null);
        expect(as(employee, delete("/api/v1/employees/37")), 405, "DELETE_FORBIDDEN", -20504);
        expect(as(admin, delete("/api/v1/employees/37")), 405, "DELETE_FORBIDDEN", -20504);
        expect(as(admin, get("/api/v1/employees/424242/history")), 404, "EMPLOYEE_NOT_FOUND", -20001);
    }

    @Test
    void updateIsPartialAndAudited() throws Exception {
        send(admin, patch("/api/v1/employees/22"), Map.of("firstName", "tom", "email", "Tom.Baker@Company.com",
                "city", "New York", "empNumber", "EMP-999999", "employmentStatus", "TERMINATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.firstName").value("TOM"))
                .andExpect(jsonPath("$.employee.lastName").value("BAKER"))
                .andExpect(jsonPath("$.employee.email").value("tom.baker@company.com"))
                .andExpect(jsonPath("$.employee.empNumber").value("EMP-000022")) // FRM-038: ignored
                .andExpect(jsonPath("$.employee.employmentStatus").value("ACTIVE"));
        Map<String, Object> row = jdbc.queryForMap("SELECT city, modified_by FROM hrms.employees WHERE emp_id = 22");
        assertThat(row).containsEntry("city", "New York").containsEntry("modified_by", "james.richardson@company.com");
        assertThat(jdbc.queryForObject("SELECT old_values->>'firstName' || '>' || (new_values->>'firstName') FROM "
                + "hrms.audit_log WHERE table_name = 'EMPLOYEES' AND record_id = 22 AND action_type = 'UPDATE'",
                String.class)).isEqualTo("THOMAS>TOM");
        expect(send(admin, patch("/api/v1/employees/22"), Map.of("email", "lisa.wong@company.com")), 409,
                "EMAIL_IN_USE", -20502);
        expect(send(admin, patch("/api/v1/employees/22"), Map.of("lastName", " ")), 400, "VALIDATION_FAILED", -20010);
        expect(send(admin, patch("/api/v1/employees/424242"), Map.of("city", "X")), 404, "EMPLOYEE_NOT_FOUND", -20001);
    }

    @Test
    void transferValidatesAndWritesHistory() throws Exception {
        send(admin, post("/api/v1/employees/34/transfer"), Map.of("newDeptId", 32, "newManagerEmpId", 33,
                "reasonCode", "REORG")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.deptId").value(32))
                .andExpect(jsonPath("$.employee.managerEmpId").value(33))
                .andExpect(jsonPath("$.employee.jobId").value(50)) // unchanged
                .andExpect(jsonPath("$.employee.locationCode").value("CHI"));
        Map<String, Object> h = jdbc.queryForMap("SELECT * FROM hrms.employee_history WHERE emp_id = 34");
        assertThat(h).containsEntry("change_type", "TRANSFER").containsEntry("old_dept_id", 31L)
                .containsEntry("new_dept_id", 32L).containsEntry("old_manager_id", 31L)
                .containsEntry("new_manager_id", 33L).containsEntry("reason_code", "REORG")
                .containsEntry("effective_date", java.sql.Date.valueOf("2026-03-02"));
        expect(send(admin, post("/api/v1/employees/" + inactive("SUSPENDED") + "/transfer"), Map.of("newDeptId", 32)), 422,
                "EMPLOYEE_NOT_ACTIVE", -20012);
        expect(send(admin, post("/api/v1/employees/31/transfer"), Map.of("newDeptId", 31, "newManagerEmpId", 37)),
                422, "CIRCULAR_REPORTING", -20004); // 37 reports to 31
        expect(send(admin, post("/api/v1/employees/31/transfer"), Map.of("newDeptId", 31, "newManagerEmpId", 31)),
                422, "CIRCULAR_REPORTING", -20004);
        expect(send(admin, post("/api/v1/employees/31/transfer"), Map.of("newDeptId", 999)), 422,
                "INVALID_DEPARTMENT", -20003);
        expect(send(admin, post("/api/v1/employees/31/transfer"), Map.of("newDeptId", 31, "newJobId", 999)), 422,
                "INVALID_JOB", -20011);
    }

    @Test
    void circularCheckWalksAtMostFifteenLevels() throws Exception {
        long root = insert(null);
        long[] chain = new long[17];
        chain[0] = root;
        for (int i = 1; i < chain.length; i++) {
            chain[i] = insert(chain[i - 1]);
        }
        // chain[14] -> ... -> chain[1] -> root is 14 hops above the manager: detected
        expect(send(admin, post("/api/v1/employees/" + root + "/transfer"), Map.of("newDeptId", 31,
                "newManagerEmpId", chain[14])), 422, "CIRCULAR_REPORTING", -20004);
        // root is 15 hops above chain[15]: beyond c_max_hierarchy_depth, accepted (legacy parity)
        send(admin, post("/api/v1/employees/" + root + "/transfer"), Map.of("newDeptId", 31,
                "newManagerEmpId", chain[15])).andExpect(status().isOk());
    }

    @Test
    void promoteGoesThroughThePort() throws Exception {
        send(admin, post("/api/v1/employees/33/promote"), Map.of("newJobId", 30, "newSalary", 100000,
                "effectiveDate", "2026-04-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.jobId").value(30))
                .andExpect(jsonPath("$.warnings[0].message").value(
                        "Salary $100,000.00 is below minimum for grade Manager ($110,000.00)"));
        List<Map<String, Object>> sal = salaries(33);
        assertSalary(sal.get(0), "2023-01-01", "2026-03-31", "110000", "Annual review", false);
        assertSalary(sal.get(1), "2026-04-01", null, "100000", "PROMOTION", true);
        assertThat((BigDecimal) sal.get(1).get("change_pct")).isEqualByComparingTo("-9.09"); // DEF-P5-2
        Map<String, Object> h = jdbc.queryForMap("SELECT * FROM hrms.employee_history WHERE emp_id = 33");
        assertThat(h).containsEntry("change_type", "PROMOTION").containsEntry("old_job_id", 41L)
                .containsEntry("new_job_id", 30L);
        assertThat(((BigDecimal) h.get("new_salary"))).isEqualByComparingTo("100000");
        assertThat(((BigDecimal) h.get("old_salary"))).isEqualByComparingTo("110000");
        expect(send(admin, post("/api/v1/employees/" + inactive("ON_LEAVE") + "/promote"), Map.of("newJobId", 30,
                "newSalary", 1)), 422,
                "EMPLOYEE_NOT_ACTIVE", -20001);
        expect(send(admin, post("/api/v1/employees/33/promote"), Map.of("newJobId", 30, "newSalary", -5)), 422,
                "INVALID_SALARY", -20101);
    }

    @Test
    void terminateCancelsPendingLeaveThroughTheLeaveServiceAndEndsCompensation() throws Exception {
        jdbc.update("INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, "
                + "created_by) VALUES (35, 1, 2026, 10, 'test') ON CONFLICT DO NOTHING");
        String chris = tokenFor(35);
        long requestId = read(send(chris, post("/api/v1/leave/requests"), Map.of("leaveTypeId", 1,
                "startDate", "2026-03-09", "endDate", "2026-03-10")).andExpect(status().isCreated()))
                .get("requestId").asLong();
        assertThat(jdbc.queryForObject("SELECT pending FROM hrms.leave_balances WHERE emp_id = 35 AND "
                + "leave_type_id = 1 AND calendar_year = 2026", Double.class)).isPositive();

        send(admin, post("/api/v1/employees/35/terminate"), Map.of("terminationDate", "2026-03-31",
                "reason", "VOLUNTARY", "comments", "Moving away")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.employmentStatus").value("TERMINATED"))
                .andExpect(jsonPath("$.employee.active").value(false));

        Map<String, Object> req = jdbc.queryForMap("SELECT status, cancel_reason FROM hrms.leave_requests "
                + "WHERE request_id = ?", requestId);
        assertThat(req).containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Auto-cancelled due to termination");
        assertThat(jdbc.queryForObject("SELECT pending FROM hrms.leave_balances WHERE emp_id = 35 AND "
                + "leave_type_id = 1 AND calendar_year = 2026", Double.class)).isZero(); // DEF-22
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.audit_log WHERE table_name = 'LEAVE_REQUESTS' "
                + "AND record_id = ? AND action_type = 'STATUS_CHANGE'", Long.class, requestId)).isEqualTo(1);
        Map<String, Object> emp = jdbc.queryForMap("SELECT termination_date, termination_reason FROM "
                + "hrms.employees WHERE emp_id = 35");
        assertThat(emp).containsEntry("termination_reason", "VOLUNTARY")
                .containsEntry("termination_date", java.sql.Date.valueOf("2026-03-31"));
        assertThat(salaries(35)).singleElement().satisfies(r -> assertSalary(r, "2023-01-01", "2026-03-31", "78000",
                "Annual review", false));
        assertThat(jdbc.queryForMap("SELECT change_type, reason_code, comments FROM hrms.employee_history "
                + "WHERE emp_id = 35")).containsEntry("change_type", "TERMINATION")
                .containsEntry("reason_code", "VOLUNTARY").containsEntry("comments", "Moving away");
        assertThat(jdbc.queryForObject("SELECT new_values->>'employmentStatus' FROM hrms.audit_log WHERE "
                + "table_name = 'EMPLOYEES' AND record_id = 35 AND action_type = 'STATUS_CHANGE'", String.class))
                .isEqualTo("TERMINATED");
        assertThat(jdbc.queryForMap("SELECT recipient_emp_id, subject, body FROM hrms.notification_queue WHERE "
                + "reference_table = 'EMPLOYEES' AND reference_id = 35")).containsEntry("recipient_emp_id", 31L)
                .containsEntry("subject", "Employee Termination: CHRIS ANDERSON")
                .containsEntry("body", "CHRIS ANDERSON termination effective 03/31/2026");
        expect(send(admin, post("/api/v1/employees/35/terminate"), Map.of("terminationDate", "2026-03-31",
                "reason", "VOLUNTARY")), 409, "ALREADY_TERMINATED", -20005);
        expect(send(admin, post("/api/v1/employees/35/terminate"), Map.of("terminationDate", "2026-03-31")), 400,
                "VALIDATION_FAILED", null);
    }

    @Test
    void rehireIsTheOnlyWayBackFromTerminated() throws Exception {
        expect(send(admin, post("/api/v1/employees/36/rehire"), rehire()), 409, "EMPLOYEE_NOT_TERMINATED", null);
        long holder = read(send(admin, post("/api/v1/employees"), hire("Bri", "Holder", "Brian.Foster@company.com",
                null, 50000)).andExpect(status().isCreated())).get("employee").get("empId").asLong();
        expect(send(admin, post("/api/v1/employees/99/rehire"), rehire()), 409, "EMAIL_IN_USE", -20502);
        send(admin, patch("/api/v1/employees/" + holder), Map.of("email", "bri.holder@company.com"))
                .andExpect(status().isOk());
        expect(send(admin, post("/api/v1/employees/99/rehire"), with(rehire(), "deptId", 999)), 422,
                "INVALID_DEPARTMENT", -20003);

        send(admin, post("/api/v1/employees/99/rehire"), rehire()).andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.employee.active").value(true))
                .andExpect(jsonPath("$.employee.hireDate").value("2026-03-16"))
                .andExpect(jsonPath("$.employee.deptId").value(20))
                .andExpect(jsonPath("$.employee.locationCode").value("HQ")) // dept 20's location
                .andExpect(jsonPath("$.employee.managerEmpId").value(2))
                .andExpect(jsonPath("$.warnings").isEmpty());
        assertThat(jdbc.queryForMap("SELECT termination_date, termination_reason FROM hrms.employees WHERE emp_id = 99"))
                .containsEntry("termination_date", null).containsEntry("termination_reason", null);
        assertThat(salaries(99)).last().satisfies(r -> assertSalary(r, "2026-03-16", null, "75000", "REHIRE", true));
        JsonNode history = read(as(admin, get("/api/v1/employees/99/history")).andExpect(status().isOk()));
        assertThat(history.findValuesAsText("changeType")).containsExactly("REHIRE");
        assertThat(history.get(0).get("oldDeptId").asLong()).isEqualTo(31);
        assertThat(history.get(0).get("newLocation").asText()).isEqualTo("HQ");
        expect(send(admin, post("/api/v1/employees/99/rehire"), rehire()), 409, "EMPLOYEE_NOT_TERMINATED", null);
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> salaries(long empId) {
        return jdbc.queryForList("SELECT * FROM hrms.salary_records WHERE emp_id = ? ORDER BY effective_date, "
                + "salary_id", empId);
    }

    private static void assertSalary(Map<String, Object> r, String effective, String end, String salary,
            String reason, boolean active) {
        assertThat(r).containsEntry("effective_date", java.sql.Date.valueOf(effective))
                .containsEntry("end_date", end == null ? null : java.sql.Date.valueOf(end))
                .containsEntry("change_reason", reason).containsEntry("active_flag", active);
        assertThat((BigDecimal) r.get("base_salary")).isEqualByComparingTo(salary);
    }

    private long inactive(String status) {
        long id = insert(null);
        jdbc.update("UPDATE hrms.employees SET employment_status = ?, active_flag = ?, email = ? WHERE emp_id = ?",
                status, !"TERMINATED".equals(status), "gone" + id + "@company.com", id);
        return id;
    }

    private long insert(Long manager) {
        long id = jdbc.queryForObject("SELECT nextval('hrms.seq_emp_number')", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO hrms.employees (emp_number, first_name, last_name, hire_date, dept_id, job_id,
                    manager_emp_id, location_code, employment_status, created_by)
                VALUES (?, 'CHAIN', 'LINK', DATE '2020-01-01', 31, 50, ?, 'CHI', 'ACTIVE', 'test') RETURNING emp_id
                """, Long.class, String.format("EMP-%06d", id), manager);
    }

    private static Map<String, Object> hire(String first, String last, String email, Long manager, int salary) {
        Map<String, Object> m = new HashMap<>(Map.of("firstName", first, "lastName", last, "hireDate", "2026-03-16",
                "deptId", 31, "jobId", 60, "baseSalary", salary));
        if (email != null) {
            m.put("email", email);
        }
        if (manager != null) {
            m.put("managerEmpId", manager);
        }
        return m;
    }

    private static Map<String, Object> rehire() {
        return new HashMap<>(Map.of("rehireDate", "2026-03-16", "deptId", 20, "jobId", 52, "baseSalary", 75000,
                "managerEmpId", 2));
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        Map<String, Object> m = new HashMap<>(body);
        m.put(key, value);
        return m;
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, Map<String, Object> body)
            throws Exception {
        return as(token, request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
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
