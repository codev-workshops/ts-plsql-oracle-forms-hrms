package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** /api/reference/*, /api/employees and /legacy/sso/exchange against the frozen seed. */
class ReferenceAndSsoTest extends AuthApiTestBase {

  private String execToken;
  private String staffToken;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    execToken = token(EXEC_EMAIL);
    staffToken = token(STAFF_EMAIL);
  }

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andReturn();
    return json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
  }

  @Test
  void referenceEndpointsAreAuthenticatedCachedAndEtagged() throws Exception {
    mvc.perform(get("/api/reference/departments")).andExpect(status().isUnauthorized());
    MvcResult r =
        mvc.perform(
                get("/api/reference/departments").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, private"))
            .andExpect(jsonPath("$[0].deptCode").exists())
            .andExpect(jsonPath("$[0].active").value(true))
            .andReturn();
    String etag = r.getResponse().getHeader("ETag");
    assertThat(etag).isNotBlank();
    mvc.perform(
            get("/api/reference/departments")
                .header("Authorization", "Bearer " + staffToken)
                .header("If-None-Match", etag))
        .andExpect(status().isNotModified());

    mvc.perform(get("/api/reference/job-titles").header("Authorization", "Bearer " + staffToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].jobId").exists())
        .andExpect(
            jsonPath("$[0].gradeMinSalary")
                .value(org.hamcrest.Matchers.matchesPattern("^[0-9]+\\.[0-9]{2}$")));
    mvc.perform(get("/api/reference/locations").header("Authorization", "Bearer " + staffToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].locationCode").exists());
    mvc.perform(get("/api/reference/leave-types").header("Authorization", "Bearer " + staffToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].leaveTypeCode").exists())
        .andExpect(jsonPath("$[0].paid").isBoolean());
  }

  @Test
  void employeeSearchIsContractExactAndExcludesSelfFromJwt() throws Exception {
    MvcResult r =
        mvc.perform(
                get("/api/employees")
                    .param("status", "ACTIVE")
                    .param("fields", "id,name,jobTitle")
                    .param("excludeSelf", "true")
                    .param("size", "100")
                    .header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page = json.readTree(r.getResponse().getContentAsString());
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(100);
    assertThat(page.get("totalElements").asLong()).isGreaterThan(0);
    for (JsonNode e : page.get("content")) {
      assertThat(e.get("id").asLong()).isNotEqualTo(2L);
      assertThat(e.fieldNames())
          .toIterable()
          .containsExactlyInAnyOrder("id", "empNumber", "name", "jobTitle");
    }
    assertThat(page.get("content").get(0).get("name").asText()).contains(" ");

    mvc.perform(
            get("/api/employees")
                .param("status", "ACTIVE")
                .param("fields", "id,name,jobTitle")
                .param("q", "chen")
                .header("Authorization", "Bearer " + execToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(2))
        .andExpect(jsonPath("$.content[0].name").value("SARAH CHEN"))
        .andExpect(jsonPath("$.totalElements").value(1));

    mvc.perform(
            get("/api/employees")
                .param("status", "TERMINATED")
                .param("fields", "id,name,jobTitle")
                .header("Authorization", "Bearer " + execToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("status"));
    mvc.perform(
            get("/api/employees")
                .param("status", "ACTIVE")
                .header("Authorization", "Bearer " + execToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void ssoExchangeIsProxyOnlyAndModuleAware() throws Exception {
    // auth is NEW -> 403 SSO_MODULE_NOT_LEGACY
    mvc.perform(
            post("/legacy/sso/exchange")
                .header("Authorization", "Bearer " + execToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("module", "auth", "clientIp", "10.1.1.1"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SSO_MODULE_NOT_LEGACY"));
    // payroll is LEGACY but no Oracle configured -> 502
    mvc.perform(
            post("/legacy/sso/exchange")
                .header("Authorization", "Bearer " + execToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("module", "payroll", "clientIp", "10.1.1.1"))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("SSO_LEGACY_UNAVAILABLE"));
    // outside the proxy CIDR
    mvc.perform(
            post("/legacy/sso/exchange")
                .with(
                    req -> {
                      req.setRemoteAddr("203.0.113.9");
                      return req;
                    })
                .header("Authorization", "Bearer " + execToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("module", "payroll", "clientIp", "10.1.1.1"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SSO_MODULE_NOT_LEGACY"));
    mvc.perform(
            post("/legacy/sso/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("module", "payroll", "clientIp", "10.1.1.1"))))
        .andExpect(status().isUnauthorized());
  }
}
