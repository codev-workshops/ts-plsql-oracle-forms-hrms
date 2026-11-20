package com.hrms.leave;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hrms.auth.ApiTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Leave fixtures. "Today" is Monday 2026-03-02 (UTC). 37 (CHI, grade 2) reports to 31; 36 is a peer;
 * emp 1 has every permission (grade >= 8). CHI has a holiday on 2026-03-04, HQ on 2026-03-05.
 */
abstract class LeaveTestSupport extends ApiTestSupport {

    static final Instant TODAY = Instant.parse("2026-03-02T09:00:00Z");
    static final long PTO = 1;
    static final long SICK = 2;
    static final long COMP = 3;
    static final long FMLA = 4;
    static final long JURY = 5;

    @BeforeEach
    void resetLeaveData() {
        clock.set(TODAY);
        jdbc.update("DELETE FROM hrms.leave_accrual_log");
        jdbc.update("DELETE FROM hrms.leave_accrual_runs");
        jdbc.update("DELETE FROM hrms.leave_requests");
        jdbc.update("DELETE FROM hrms.notification_queue");
        jdbc.update("DELETE FROM hrms.audit_log WHERE table_name = 'LEAVE_REQUESTS'");
        jdbc.update("DELETE FROM hrms.leave_balances");
        jdbc.update("DELETE FROM hrms.holidays WHERE holiday_date >= DATE '2026-01-01'");
        jdbc.update("""
                INSERT INTO hrms.holidays (holiday_name, holiday_date, location_code, active_flag, created_by)
                VALUES ('CHI day', DATE '2026-03-04', 'CHI', true, 'test'),
                       ('HQ day', DATE '2026-03-05', 'HQ', true, 'test'),
                       ('Inactive', DATE '2026-03-03', NULL, false, 'test')
                """);
        jdbc.update("""
                INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, created_by)
                SELECT e.emp_id, t.leave_type_id, 2026,
                       CASE t.leave_type_code WHEN 'PTO' THEN 10 WHEN 'SICK' THEN 5 ELSE 0 END, 'test'
                FROM hrms.employees e CROSS JOIN hrms.leave_types t
                WHERE e.employment_status = 'ACTIVE' AND t.active_flag
                """);
    }

    protected ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    protected ResultActions submit(String token, Map<String, Object> body) throws Exception {
        return as(token, post("/api/v1/leave/requests").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions command(String token, long requestId, String verb, Map<String, Object> body)
            throws Exception {
        return as(token, post("/api/v1/leave/requests/" + requestId + "/" + verb)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected static Map<String, Object> days(long type, String start, String end) {
        return Map.of("leaveTypeId", type, "startDate", start, "endDate", end);
    }

    protected double balance(long empId, long type, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM hrms.leave_balances WHERE emp_id = ? "
                + "AND leave_type_id = ? AND calendar_year = 2026", Double.class, empId, type);
    }
}
