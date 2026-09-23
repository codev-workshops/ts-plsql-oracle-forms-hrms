package com.acme.hrms.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Employee self-service writes under {@code employee=NEW_READONLY}: 409 before any row scope. */
@TestPropertySource(properties = "hrms.proxy.modules.employee=NEW_READONLY")
class EmployeeModuleReadOnlyTest extends AuthApiTestBase {

  private String staff;

  @BeforeEach
  void seed() throws Exception {
    seedAccounts();
    staff = token(STAFF_EMAIL);
  }

  @Test
  void selfServiceWritesAreRejectedWhileReadsRemainAvailable() throws Exception {
    mvc.perform(json(post("/api/employees/2/dependents"), staff, EmployeeApiTest.dependent("A")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MODULE_READ_ONLY"));
    mvc.perform(json(post("/api/employees/2/contacts"), staff, EmployeeApiTest.contact()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MODULE_READ_ONLY"));
    mvc.perform(json(put("/api/employees/2/dependents/1"), staff, EmployeeApiTest.dependent("A")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MODULE_READ_ONLY"));
    // the module flag wins over an unknown body property (error-codes.md §3 step 3 before 5)
    Map<String, Object> unknown = new HashMap<>(EmployeeApiTest.contact());
    unknown.put("contactId", null);
    mvc.perform(json(post("/api/employees/2/contacts"), staff, unknown))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MODULE_READ_ONLY"));
    mvc.perform(get("/api/employees/2/dependents").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk());
    mvc.perform(get("/api/employees/1").header("Authorization", "Bearer " + staff))
        .andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String token, Object value) throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(value))
        .header("Authorization", "Bearer " + token);
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
