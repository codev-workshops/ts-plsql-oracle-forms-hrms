package com.hrms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Audit viewer over HTTP (PKG_AUDIT read/purge). Admin = James Richardson (grade 10); Jennifer Park
 * (grade 6) and Thomas Baker (grade 4) are denied. Each test seeds its own AUDIT_LOG rows on a test-only
 * table name; clock fixed at 2026-11-19 09:00.
 */
class AuditApiTest extends IntegrationTest {

    private static final String TABLE = "AUDIT_TEST_TABLE";

    private String admin;
    private String manager;
    private String employee;
    private long first;
    private long second;
    private long third;
    private long legacy;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("james.richardson@company.com");
        manager = login("jennifer.park@company.com");
        employee = login("thomas.baker@company.com");
        first = insert(TABLE, 1, "INSERT", null, "{\"status\":\"PENDING\"}", "alice", NOW.minusDays(10));
        second = insert(TABLE, 1, "UPDATE", "{\"status\":\"PENDING\"}", "{\"status\":\"APPROVED\"}", "Bob",
                NOW.minusDays(5));
        third = insert(TABLE, 2, "DELETE", "{\"status\":\"APPROVED\"}", null, "alice", NOW.minusDays(1));
        legacy = insert(TABLE, 3, "UPDATE", "old free text", "[1,2]", "legacy", NOW.minusDays(400));
    }

    private long insert(String table, long recordId, String action, String oldValues, String newValues, String by,
                        LocalDateTime when) {
        return jdbc.queryForObject("""
                INSERT INTO audit_log (table_name, record_id, action_type, old_values, new_values, changed_by,
                                       changed_date)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING audit_id
                """, Long.class, table, recordId, action, oldValues, newValues, by, Timestamp.valueOf(when));
    }

    private List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("auditId").asLong()));
        return ids;
    }

    private List<Long> searchIds(String query) throws Exception {
        return ids(read(call(get("/api/v1/audit/entries?tableName=" + TABLE + query), admin)
                .andExpect(status().isOk())));
    }

    @Test
    void searchReturnsNewestFirstWithParsedValues() throws Exception {
        JsonNode entries = read(call(get("/api/v1/audit/entries").param("tableName", TABLE), admin)
                .andExpect(status().isOk()));
        assertThat(ids(entries)).containsExactly(third, second, first, legacy);
        JsonNode updated = entries.get(1);
        assertThat(updated.get("tableName").asText()).isEqualTo(TABLE);
        assertThat(updated.get("recordId").asLong()).isEqualTo(1);
        assertThat(updated.get("action").asText()).isEqualTo("UPDATE");
        assertThat(updated.get("oldValues").get("status").asText()).isEqualTo("PENDING");
        assertThat(updated.get("newValues").get("status").asText()).isEqualTo("APPROVED");
        assertThat(updated.get("changedBy").asText()).isEqualTo("Bob");
        assertThat(updated.get("changedDate").asText()).isEqualTo("2026-11-14T09:00:00");
        assertThat(entries.get(0).has("newValues")).isFalse();
    }

    @Test
    void searchFilters() throws Exception {
        assertThat(searchIds("&recordId=1")).containsExactly(second, first);
        assertThat(searchIds("&action=DELETE")).containsExactly(third);
        assertThat(searchIds("&changedBy=ALICE")).containsExactly(third, first);
        assertThat(searchIds("&from=2026-11-14&to=2026-11-18")).containsExactly(third, second);
        assertThat(searchIds("&from=2026-11-14&to=2026-11-14")).containsExactly(second);
        assertThat(searchIds("&limit=2")).containsExactly(third, second);
        assertThat(ids(read(call(get("/api/v1/audit/entries?tableName=audit_test_table&recordId=2"), admin))))
                .containsExactly(third);
    }

    @Test
    void searchDefaultsToHundredRowsAndRejectsBadInput() throws Exception {
        for (int i = 0; i < 105; i++) {
            insert(TABLE, 99, "INSERT", null, null, "bulk", NOW.minusHours(i));
        }
        assertThat(searchIds("&recordId=99")).hasSize(100);
        assertThat(searchIds("&recordId=99&limit=1000")).hasSize(105);
        for (String query : new String[] {"limit=1001", "limit=0", "action=MERGE", "from=2026-11-19&to=2026-11-01",
                "from=not-a-date", "recordId=abc"}) {
            call(get("/api/v1/audit/entries?" + query), admin)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("-20000"));
        }
    }

    @Test
    void getEntryAndLegacyTextValues() throws Exception {
        call(get("/api/v1/audit/entries/" + second), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").value(second))
                .andExpect(jsonPath("$.newValues.status").value("APPROVED"));
        call(get("/api/v1/audit/entries/" + legacy), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldValues.value").value("old free text"))
                .andExpect(jsonPath("$.newValues.value").value("[1,2]"));
        call(get("/api/v1/audit/entries/999999999"), admin)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("-20990"));
    }

    @Test
    void recordTrailIsOldestFirstAndCaseInsensitive() throws Exception {
        JsonNode trail = read(call(get("/api/v1/audit/records/audit_test_table/1"), admin)
                .andExpect(status().isOk()));
        assertThat(ids(trail)).containsExactly(first, second);
        call(get("/api/v1/audit/records/" + TABLE + "/42"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void viewerIsAdminOnly() throws Exception {
        for (String token : new String[] {manager, employee}) {
            call(get("/api/v1/audit/entries"), token)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20304"));
            call(get("/api/v1/audit/entries/" + first), token)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20304"));
            call(get("/api/v1/audit/records/" + TABLE + "/1"), token)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20304"));
            call(post("/api/v1/audit/purge"), token)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("-20304"));
        }
        assertThat(searchIds("")).hasSize(4);
        mvc.perform(get("/api/v1/audit/entries")).andExpect(status().isUnauthorized());
    }

    @Test
    void purgeUsesRetentionParameterByDefault() throws Exception {
        long ancient = insert(TABLE, 4, "INSERT", null, null, "legacy", NOW.minusDays(2556));
        long kept = insert(TABLE, 5, "INSERT", null, null, "legacy", NOW.minusDays(2554));
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE changed_date < ?", Integer.class,
                Timestamp.valueOf(NOW.minusDays(2555)));
        call(post("/api/v1/audit/purge"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("PURGE_AUDIT_LOG"))
                .andExpect(jsonPath("$.rowsAffected").value(before));
        assertThat(before).isGreaterThanOrEqualTo(1);
        assertThat(exists(ancient)).isFalse();
        assertThat(exists(kept)).isTrue();
        assertThat(exists(legacy)).isTrue();

        JsonNode purgeAudit = read(call(get("/api/v1/audit/entries?tableName=AUDIT_LOG&action=DELETE"), admin));
        assertThat(purgeAudit.get(0).get("changedBy").asText()).isEqualTo("james.richardson@company.com");
        assertThat(purgeAudit.get(0).get("newValues").get("retentionDays").asInt()).isEqualTo(2555);
        assertThat(purgeAudit.get(0).get("newValues").get("rowsDeleted").asInt()).isEqualTo(before);
    }

    @Test
    void purgeWithExplicitRetentionAndMinimum() throws Exception {
        callJson(post("/api/v1/audit/purge"), admin, Map.of("retentionDays", 30))
                .andExpect(status().isOk());
        assertThat(exists(legacy)).isFalse();
        assertThat(exists(first)).isTrue();
        assertThat(exists(third)).isTrue();

        callJson(post("/api/v1/audit/purge"), admin, Map.of("retentionDays", 29))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("-20000"));
        call(post("/api/v1/audit/purge").contentType(MediaType.APPLICATION_JSON).content("{}"), admin)
                .andExpect(status().isOk());
    }

    @Test
    void retentionParameterBelowMinimumIsRejected() throws Exception {
        jdbc.update("UPDATE system_parameters SET param_value = '7' WHERE param_group = 'AUDIT'"
                + " AND param_code = 'RETENTION_DAYS'");
        call(post("/api/v1/audit/purge"), admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("-20000"));
        assertThat(exists(first)).isTrue();
    }

    @Test
    void entriesWrittenByOtherModulesAreVisible() throws Exception {
        JsonNode created = read(callJson(post("/api/v1/leave/requests"), employee, Map.of("leaveTypeId", 1,
                "startDate", "2026-12-07", "endDate", "2026-12-08", "reason", "audit test"))
                .andExpect(status().isCreated()));
        long requestId = created.get("requestId").asLong();
        JsonNode trail = read(call(get("/api/v1/audit/records/LEAVE_REQUESTS/" + requestId), admin)
                .andExpect(status().isOk()));
        assertThat(trail.size()).isEqualTo(1);
        assertThat(trail.get(0).get("action").asText()).isEqualTo("INSERT");
        assertThat(trail.get(0).get("changedBy").asText()).isEqualTo("thomas.baker@company.com");
    }

    private boolean exists(long auditId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE audit_id = ?", Integer.class, auditId) == 1;
    }
}
