package com.acme.hrms.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "hrms.proxy.modules.employee=NEW_READONLY")
class SalaryModuleReadOnlyTest extends AuthApiTestBase {

  private String exec;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    exec = token(EXEC_EMAIL);
  }

  @Test
  void writeIsRejectedBeforeBodyValidationWhileReadsRemainAvailable() throws Exception {
    mvc.perform(
            post("/api/employees/1/salary")
                .header("Authorization", "Bearer " + exec)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "effectiveDate", "2025-01-01",
                            "baseSalary", 0,
                            "changeReason", "MERIT"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MODULE_READ_ONLY"));
    mvc.perform(get("/api/employees/1/salary").header("Authorization", "Bearer " + exec))
        .andExpect(status().isOk());
  }

  private String token(String email) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of("username", email, "password", PASSWORD))))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("accessToken")
        .asText();
  }
}
