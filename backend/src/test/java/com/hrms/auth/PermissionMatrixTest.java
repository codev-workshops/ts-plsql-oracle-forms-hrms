package com.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** Legacy has_permission parity (D-06): grade ≥ 8 all; grade ≥ 5 VIEW all; everyone LEAVE CREATE/VIEW, EMPLOYEE VIEW. */
class PermissionMatrixTest extends ApiTestSupport {

    @Autowired
    PermissionService permissions;

    @Test
    void gradeEightAndAboveHaveEverything() {
        Set<Permission> expected = new HashSet<>();
        for (Module m : Module.values()) {
            for (Action a : Action.values()) {
                expected.add(new Permission(m, a));
            }
        }
        assertThat(permissions.permissionsOf(1)).isEqualTo(expected);   // CEO, grade 10
        assertThat(permissions.permissionsOf(2)).isEqualTo(expected);   // Director of IT, grade 8
    }

    @Test
    void gradeFiveToSevenViewEverythingPlusBaseline() {
        Set<Permission> expected = new HashSet<>(baseline());
        for (Module m : Module.values()) {
            expected.add(new Permission(m, Action.VIEW));
        }
        assertThat(permissions.permissionsOf(31)).isEqualTo(expected);  // Development Manager, grade 6
    }

    @Test
    void belowGradeFiveGetsOnlyTheBaseline() {
        assertThat(permissions.permissionsOf(32)).isEqualTo(baseline()); // grade 4
        assertThat(permissions.permissionsOf(37)).isEqualTo(baseline()); // grade 2
        assertThat(permissions.hasPermission(37, Module.PAYROLL, Action.VIEW)).isFalse();
        assertThat(permissions.hasPermission(37, Module.LEAVE, Action.APPROVE)).isFalse();
    }

    @Test
    void unknownEmployeeHasNoPermissions() {
        assertThat(permissions.permissionsOf(424242)).isEmpty();
    }

    @Test
    void meReportsPermissionsGroupedByModule() throws Exception {
        String token = tokenFor(37);
        JsonNode me = read(mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()));
        assertThat(me.get("permissions").size()).isEqualTo(2);
        assertThat(me.at("/permissions/LEAVE").toString()).isEqualTo("[\"VIEW\",\"CREATE\"]");
        assertThat(me.at("/permissions/EMPLOYEE").toString()).isEqualTo("[\"VIEW\"]");
        assertThat(me.get("empNumber").asText()).isEqualTo("EMP-000037");
    }

    @Test
    void menuFollowsTheLegacyGates() throws Exception {
        String low = tokenFor(32);
        mvc.perform(get("/api/v1/menu").header(HttpHeaders.AUTHORIZATION, bearer(low)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[?(@.id=='EMPLOYEES')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.id=='LEAVE')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.id=='PERFORMANCE')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.id=='PAYROLL')].enabled").value(false))
                .andExpect(jsonPath("$.modules[?(@.id=='REPORTS')].enabled").value(false))
                .andExpect(jsonPath("$.modules[?(@.id=='ADMIN')]").isEmpty())
                .andExpect(jsonPath("$.account[*].id").value(org.hamcrest.Matchers.contains("CHANGE_PASSWORD", "LOGOUT")));
        String manager = tokenFor(31);
        mvc.perform(get("/api/v1/menu").header(HttpHeaders.AUTHORIZATION, bearer(manager)))
                .andExpect(jsonPath("$.modules[?(@.id=='PAYROLL')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.id=='REPORTS')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.id=='REPORTS')].apiPath").value("/api/v1/reports"))
                .andExpect(jsonPath("$.modules[?(@.id=='EMPLOYEES')].route").value("/employees"))
                .andExpect(jsonPath("$.account[?(@.id=='CHANGE_PASSWORD')].apiPath")
                        .value("/api/v1/auth/change-password"))
                .andExpect(jsonPath("$.account[?(@.id=='LOGOUT')].apiPath").value("/api/v1/auth/logout"));
    }

    @Test
    void disabledRuleIsNoLongerGranted() {
        jdbc.update("UPDATE hrms.permission_rules SET active_flag = false WHERE rule_id = 5");
        try {
            assertThat(permissions.hasPermission(37, Module.EMPLOYEE, Action.VIEW)).isFalse();
        } finally {
            jdbc.update("UPDATE hrms.permission_rules SET active_flag = true WHERE rule_id = 5");
        }
    }

    private static Set<Permission> baseline() {
        return Set.of(new Permission(Module.LEAVE, Action.CREATE), new Permission(Module.LEAVE, Action.VIEW),
                new Permission(Module.EMPLOYEE, Action.VIEW));
    }
}
