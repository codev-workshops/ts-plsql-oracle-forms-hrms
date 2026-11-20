package com.hrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PKG-L05 / DEF-03 (P8). Seed PTO: CARRYOVER_MAX 5, CARRYOVER_EXPIRY 3 months; SICK: max 10, no expiry; the
 * other types have CARRYOVER_MAX 0.
 */
class LeaveCarryoverTest extends LeaveTestSupport {

    String admin;

    @BeforeEach
    void legacyYear() throws Exception {
        jdbc.update("DELETE FROM hrms.leave_carryover_log");
        jdbc.update("DELETE FROM hrms.leave_carryover_runs");
        jdbc.update("""
                INSERT INTO hrms.leave_balances (emp_id, leave_type_id, calendar_year, opening_balance, accrued, used,
                    adjustment, pending, created_by)
                VALUES (37, 1, 2025, 10, 2, 4, 0, 1, 'test'), (37, 2, 2025, 3, 0, 0, 0, 0, 'test'),
                       (36, 1, 2025, 2, 0, 0, 0, 0, 'test'), (36, 3, 2025, 4, 0, 0, 0, 0, 'test'),
                       (35, 1, 2025, 1, 0, 2, 0, 0, 'test')
                """);
        admin = tokenFor(1);
    }

    @Test
    void carryoverKeepsTheLegacyRulesAndRunsOncePerYear() throws Exception {
        expect(run(tokenFor(37), "carryover-runs", Map.of("year", 2025)), 403, "FORBIDDEN");
        expect(run(admin, "carryover-runs", Map.of()), 400, "VALIDATION_FAILED");
        long runId = read(run(admin, "carryover-runs", Map.of("year", 2025)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.runType").value("CARRYOVER")).andExpect(jsonPath("$.fromYear").value(2025))
                .andExpect(jsonPath("$.balancesProcessed").value(3))
                .andExpect(jsonPath("$.totalDays").value(10.0))
                .andExpect(jsonPath("$.createdBy").value("james.richardson@company.com"))).get("runId").asLong();
        // remaining 10 + 2 - 4 = 8 (pending excluded, as legacy) capped at CARRYOVER_MAX 5; expires 1 Jan + 3 months
        assertThat(jdbc.queryForMap("SELECT opening_balance, carryover_from_prev, carryover_expiry_dt FROM "
                + "hrms.leave_balances WHERE emp_id = 37 AND leave_type_id = 1 AND calendar_year = 2026"))
                .containsEntry("opening_balance", new java.math.BigDecimal("5.00"))
                .containsEntry("carryover_from_prev", new java.math.BigDecimal("5.00"))
                .containsEntry("carryover_expiry_dt", java.sql.Date.valueOf("2026-04-01"));
        assertThat(balance(37, SICK, "carryover_from_prev")).isEqualTo(3.0);
        assertThat(jdbc.queryForObject("SELECT carryover_expiry_dt FROM hrms.leave_balances WHERE emp_id = 37 "
                + "AND leave_type_id = 2 AND calendar_year = 2026", java.sql.Date.class)).isNull();
        assertThat(balance(36, COMP, "carryover_from_prev")).isZero(); // CARRYOVER_MAX 0
        assertThat(balance(35, PTO, "carryover_from_prev")).isZero(); // nothing remaining
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrms.leave_carryover_log WHERE run_id = ?",
                Integer.class, runId)).isEqualTo(3);

        jdbc.update("UPDATE hrms.leave_balances SET opening_balance = 7 WHERE emp_id = 37 AND leave_type_id = 1 "
                + "AND calendar_year = 2026");
        run(admin, "carryover-runs", Map.of("year", 2025)).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));
        assertThat(balance(37, PTO, "opening_balance")).isEqualTo(7.0); // a re-run changes nothing (DEF-03)
    }

    @Test
    void expiryRemovesOnlyTheUnusedCarryoverOnce() throws Exception {
        run(admin, "carryover-runs", Map.of("year", 2025)).andExpect(status().isCreated());
        jdbc.update("UPDATE hrms.leave_balances SET used = 2 WHERE emp_id = 37 AND leave_type_id = 1 "
                + "AND calendar_year = 2026");
        jdbc.update("UPDATE hrms.leave_balances SET used = 3 WHERE emp_id = 36 AND leave_type_id = 1 "
                + "AND calendar_year = 2026");

        run(admin, "carryover-expiry-runs", Map.of("asOfDate", "2026-03-31")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balancesProcessed").value(0));
        long runId = read(run(admin, "carryover-expiry-runs", Map.of("asOfDate", "2026-04-01"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.runType").value("EXPIRY"))
                .andExpect(jsonPath("$.asOfDate").value("2026-04-01"))
                .andExpect(jsonPath("$.fromYear").isEmpty())
                .andExpect(jsonPath("$.balancesProcessed").value(2))
                .andExpect(jsonPath("$.totalDays").value(3.0))).get("runId").asLong();
        // DEF-03: 5 carried, 2 used -> 3 expire (legacy subtracted all 5); 36 used more than it carried -> 0
        assertThat(balance(37, PTO, "adjustment")).isEqualTo(-3.0);
        assertThat(balance(37, PTO, "carryover_from_prev")).isZero();
        assertThat(balance(36, PTO, "adjustment")).isZero();
        assertThat(balance(37, SICK, "carryover_from_prev")).isEqualTo(3.0); // no expiry date

        run(admin, "carryover-expiry-runs", Map.of("asOfDate", "2026-04-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));
        run(admin, "carryover-expiry-runs", Map.of("asOfDate", "2026-04-02")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balancesProcessed").value(0));
        // The legacy double path: re-running process_carryover after expiry reset CARRYOVER_FROM_PREV
        run(admin, "carryover-runs", Map.of("year", 2025)).andExpect(status().isOk());
        run(admin, "carryover-expiry-runs", Map.of("asOfDate", "2026-04-03")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balancesProcessed").value(0));
        assertThat(balance(37, PTO, "adjustment")).isEqualTo(-3.0);
    }

    private ResultActions run(String token, String path, Map<String, Object> body) throws Exception {
        return as(token, post("/api/v1/leave/" + path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static void expect(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
