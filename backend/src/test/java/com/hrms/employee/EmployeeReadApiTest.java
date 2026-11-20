package com.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.hrms.auth.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class EmployeeReadApiTest extends ApiTestSupport {

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        token = tokenFor(37); // grade 2: EMPLOYEE:VIEW comes from the everyone-rule
    }

    @Test
    void getReturnsTheReadModel() throws Exception {
        perform(get("/api/v1/employees/21")).andExpect(status().isOk())
                .andExpect(jsonPath("$.empId").value(21))
                .andExpect(jsonPath("$.empNumber").value("EMP-000021"))
                .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.hireDate").value("2017-03-20"))
                .andExpect(jsonPath("$.managerEmpId").value(20))
                .andExpect(jsonPath("$.managerName").value("ROBERT KUMAR"))
                .andExpect(jsonPath("$.locationCode").value("HQ"))
                .andExpect(jsonPath("$.locationName").isNotEmpty())
                .andExpect(jsonPath("$.deptName").isNotEmpty())
                .andExpect(jsonPath("$.jobTitle").isNotEmpty())
                .andExpect(jsonPath("$.ssn").doesNotExist())
                .andExpect(jsonPath("$.dateOfBirth").doesNotExist());
        perform(get("/api/v1/employees/1")).andExpect(jsonPath("$.managerEmpId").isEmpty());
    }

    @Test
    void defaultReadsAreActiveOnly() throws Exception {
        perform(get("/api/v1/employees/99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"))
                .andExpect(jsonPath("$.legacyCode").value(-20001));
        perform(get("/api/v1/employees/99").param("includeInactive", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("TERMINATED"));
        JsonNode all = read(perform(get("/api/v1/employees").param("size", "200")).andExpect(status().isOk()));
        assertThat(all.get("total").asInt()).isEqualTo(23);
        assertThat(all.get("items").findValuesAsText("employmentStatus")).containsOnly("ACTIVE");
        JsonNode withInactive = read(perform(get("/api/v1/employees").param("includeInactive", "true")));
        assertThat(withInactive.get("total").asInt()).isEqualTo(24);
    }

    @Test
    void unknownEmployeeIs404() throws Exception {
        perform(get("/api/v1/employees/424242")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"));
        perform(get("/api/v1/employees/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchFiltersArePrefixAndExactMatches() throws Exception {
        JsonNode byName = read(perform(get("/api/v1/employees").param("lastName", "wo")));
        assertThat(byName.get("items").findValuesAsText("lastName")).containsExactly("WONG");
        JsonNode byManager = read(perform(get("/api/v1/employees").param("managerEmpId", "21")));
        assertThat(byManager.get("items").findValuesAsText("empId")).containsExactlyInAnyOrder("22", "23", "24");
        JsonNode byLocation = read(perform(get("/api/v1/employees").param("locationCode", "SF")));
        assertThat(byLocation.get("total").asInt()).isEqualTo(4);
        JsonNode byHire = read(perform(get("/api/v1/employees")
                .param("hireDateFrom", "2021-01-01").param("hireDateTo", "2021-12-31")));
        assertThat(byHire.get("items").findValuesAsText("empId")).containsExactlyInAnyOrder("36", "43");
        JsonNode byDept = read(perform(get("/api/v1/employees").param("deptId", "40")));
        assertThat(byDept.get("total").asInt()).isEqualTo(4);
    }

    @Test
    void searchIsInjectionSafe() throws Exception {
        // DEF-09: legacy search_employees concatenated p_last_name into dynamic SQL.
        JsonNode r = read(perform(get("/api/v1/employees").param("lastName", "' OR '1'='1")).andExpect(status().isOk()));
        assertThat(r.get("total").asInt()).isZero();
        JsonNode pct = read(perform(get("/api/v1/employees").param("lastName", "%")));
        assertThat(pct.get("total").asInt()).isZero();
    }

    @Test
    void pagingIsStableAndBounded() throws Exception {
        JsonNode p0 = read(perform(get("/api/v1/employees").param("size", "10").param("page", "0")));
        JsonNode p2 = read(perform(get("/api/v1/employees").param("size", "10").param("page", "2")));
        assertThat(p0.get("items").size()).isEqualTo(10);
        assertThat(p2.get("items").size()).isEqualTo(3);
        assertThat(p0.get("items").get(0).get("lastName").asText()).isEqualTo("ANDERSON");
        perform(get("/api/v1/employees").param("size", "500")).andExpect(status().isBadRequest());
        perform(get("/api/v1/employees").param("status", "BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void lookupsAreActiveOnly() throws Exception {
        perform(get("/api/v1/lookups/departments")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10));
        perform(get("/api/v1/lookups/job-titles")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(26));
        perform(get("/api/v1/lookups/locations")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        jdbc.update("UPDATE hrms.locations SET active_flag = false WHERE location_code = 'SF'");
        try {
            perform(get("/api/v1/lookups/locations")).andExpect(jsonPath("$.length()").value(2));
        } finally {
            jdbc.update("UPDATE hrms.locations SET active_flag = true WHERE location_code = 'SF'");
        }
    }

    @Test
    void employeeViewIsRequired() throws Exception {
        jdbc.update("UPDATE hrms.permission_rules SET active_flag = false WHERE rule_id = 5");
        try {
            perform(get("/api/v1/employees/21")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            perform(get("/api/v1/lookups/locations")).andExpect(status().isForbidden());
        } finally {
            jdbc.update("UPDATE hrms.permission_rules SET active_flag = true WHERE rule_id = 5");
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }
}
