package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P5 auth-owned role management: authorities vocabulary, custom-role CRUD with {@code seq_role}
 * allocation, seeded-role immutability, least-privilege grants, self-modification / last-admin
 * guards (serialized under the advisory lock), account status transitions and token freshness
 * (refresh tokens, JTI and sessions revoked on every effective role/status change).
 */
class RoleAdminApiTest extends AuthApiTestBase {

  private String admin; // user 1 (EXECUTIVE, role 3: all module:action authorities)
  private String viewer; // user 3 (MANAGER, role 2: *:VIEW + LEAVE:CREATE)

  @BeforeEach
  void reset() {
    HrmsPostgres.resetSchema();
    seedAccounts();
    admin = token(EXEC_EMAIL);
    viewer = token(MANAGER_EMAIL);
  }

  @Test
  void authoritiesVocabularyAndRoleListingNeedAdminView() throws Exception {
    mvc.perform(get("/api/admin/authorities")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/admin/authorities").header("Authorization", "Bearer " + token(STAFF_EMAIL)))
        .andExpect(status().isForbidden());
    MvcResult r =
        mvc.perform(get("/api/admin/authorities").header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode auths = body(r);
    assertThat(auths.toString())
        .contains("\"ADMIN:EDIT\"", "\"LEAVE:VIEW_ALL\"", "\"PERFORMANCE:ADMIN\"");

    MvcResult roles =
        mvc.perform(get("/api/admin/roles").header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arr = body(roles);
    assertThat(arr).hasSize(3);
    assertThat(arr.get(0).get("roleId").asInt()).isEqualTo(1);
    assertThat(arr.get(2).get("roleCode").asText()).isEqualTo("EXECUTIVE");
    assertThat(arr.get(2).get("seeded").asBoolean()).isTrue();
    assertThat(arr.get(2).get("userCount").asInt()).isEqualTo(1);
    assertThat(arr.get(2).get("permissions")).hasSize(27);

    mvc.perform(
            jsonReq(post("/api/admin/roles"), viewer, json(role("X_ROLE", List.of("LEAVE:VIEW")))))
        .andExpect(status().isForbidden());
  }

  @Test
  void customRoleLifecycleUsesSeqRoleAndAuditsJwtSub() throws Exception {
    MvcResult created =
        mvc.perform(
                jsonReq(
                    post("/api/admin/roles"),
                    admin,
                    json(role("PAYROLL_CLERK", List.of("PAYROLL:VIEW", "PAYROLL:EDIT")))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.seeded").value(false))
            .andExpect(jsonPath("$.userCount").value(0))
            .andExpect(jsonPath("$.sessionsRevoked").doesNotExist())
            .andExpect(jsonPath("$.createdBy").value("1"))
            .andReturn();
    int id = body(created).get("roleId").asInt();
    // V12 advanced seq_role: first custom id is >= 1000, never max(role_id)+1 == 4
    assertThat(id).isGreaterThanOrEqualTo(1000);
    assertThat(created.getResponse().getHeader("Location")).endsWith("/api/admin/roles/" + id);

    // code conflict (the frozen pattern is upper-case only, so exact match is the only case)
    mvc.perform(
            jsonReq(
                post("/api/admin/roles"),
                admin,
                json(role("PAYROLL_CLERK", List.of("PAYROLL:VIEW")))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20801"));

    // update permissions; code is immutable
    Map<String, Object> upd = role("PAYROLL_CLERK", List.of("PAYROLL:VIEW"));
    upd.put("roleName", "Payroll clerk (read only)");
    mvc.perform(jsonReq(put("/api/admin/roles/" + id), admin, json(upd)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.length()").value(1))
        .andExpect(jsonPath("$.roleName").value("Payroll clerk (read only)"));
    upd.put("roleCode", "PAYROLL_CLERK2");
    mvc.perform(jsonReq(put("/api/admin/roles/" + id), admin, json(upd)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20801"));

    // assigned custom roles cannot be deleted; unassigned ones are physically removed
    mvc.perform(
            jsonReq(
                put("/api/admin/users/4/roles"), admin, json(Map.of("roleIds", List.of(1, id)))))
        .andExpect(status().isOk());
    mvc.perform(jsonReq(delete("/api/admin/roles/" + id), admin, ""))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20803"));
    mvc.perform(
            jsonReq(put("/api/admin/users/4/roles"), admin, json(Map.of("roleIds", List.of(1)))))
        .andExpect(status().isOk());
    mvc.perform(jsonReq(delete("/api/admin/roles/" + id), admin, ""))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject("select count(*) from roles where role_id = ?", Integer.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from role_permissions where role_id = ?", Integer.class, id))
        .isZero();

    List<String> actors =
        jdbc.queryForList(
            "select distinct changed_by from audit_log where table_name = 'ROLES'"
                + " and record_id = cast(? as bigint)",
            String.class,
            String.valueOf(id));
    assertThat(actors).containsExactly("1");
  }

  @Test
  void seededRolesAreReadOnlyAndGrantsAreLeastPrivilege() throws Exception {
    MvcResult mgr =
        mvc.perform(get("/api/admin/roles/2").header("Authorization", "Bearer " + admin))
            .andReturn();
    Map<String, Object> same = fromRole(body(mgr));
    // identical PUT is a no-op 200; any differing field is -20802; DELETE always -20802
    mvc.perform(jsonReq(put("/api/admin/roles/2"), admin, json(same))).andExpect(status().isOk());
    same.put("roleName", "Manager renamed");
    mvc.perform(jsonReq(put("/api/admin/roles/2"), admin, json(same)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20802"));
    mvc.perform(jsonReq(delete("/api/admin/roles/1"), admin, ""))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20802"));

    // an authority outside the vocabulary is a Bean Validation / -20603 problem, not a grant
    mvc.perform(jsonReq(post("/api/admin/roles"), admin, json(role("BOGUS", List.of("FOO:BAR")))))
        .andExpect(status().isBadRequest());
    // unknown field / wrong wire type are 400 before Bean Validation
    mvc.perform(
            jsonReq(
                post("/api/admin/roles"),
                admin,
                "{\"roleCode\":\"\",\"roleName\":\"x\",\"minGrade\":1,\"maxGrade\":2,"
                    + "\"permissions\":[\"LEAVE:VIEW\"],\"seeded\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("seeded"));
  }

  @Test
  void userRolesAndStatusGuardsRevokeSessionsAndAuditActor() throws Exception {
    // self modification
    mvc.perform(
            jsonReq(put("/api/admin/users/1/roles"), admin, json(Map.of("roleIds", List.of(3)))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20804"));
    mvc.perform(
            jsonReq(put("/api/admin/users/1/status"), admin, json(Map.of("status", "DISABLED"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20804"));
    // unknown role
    mvc.perform(
            jsonReq(
                put("/api/admin/users/4/roles"), admin, json(Map.of("roleIds", List.of(1, 4242)))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20807"))
        .andExpect(jsonPath("$.field").value("roleIds"));
    // unknown user
    mvc.perform(
            jsonReq(put("/api/admin/users/9999/roles"), admin, json(Map.of("roleIds", List.of(1)))))
        .andExpect(status().isNotFound());

    // user 4 (STAFF) logs in: sessions/refresh token exist, then gets promoted to MANAGER
    String staff4 = token("david.martinez@company.com");
    mvc.perform(get("/api/employees/11").header("Authorization", "Bearer " + staff4))
        .andExpect(status().isOk());
    MvcResult promoted =
        mvc.perform(
                jsonReq(
                    put("/api/admin/users/4/roles"), admin, json(Map.of("roleIds", List.of(2)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0].roleId").value(2))
            .andExpect(jsonPath("$.roles[0].grantedBy").value("1"))
            .andReturn();
    assertThat(body(promoted).get("sessionsRevoked").asInt()).isGreaterThanOrEqualTo(1);
    // the old access token is dead (JTI revoked)
    mvc.perform(get("/api/employees/11").header("Authorization", "Bearer " + staff4))
        .andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = 4 and revoked_at is null",
                Integer.class))
        .isZero();
    // same roles again: no change -> nothing revoked, still 200
    String staff4b = token("david.martinez@company.com");
    mvc.perform(
            jsonReq(put("/api/admin/users/4/roles"), admin, json(Map.of("roleIds", List.of(2)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionsRevoked").value(0));
    mvc.perform(get("/api/employees/11").header("Authorization", "Bearer " + staff4b))
        .andExpect(status().isOk());

    // disable: locked login, sessions revoked; re-activate clears lock state / failed attempts
    jdbc.update(
        "update user_accounts set failed_attempts = 3, locked_until = now() + interval '1 hour' where user_id = 4");
    mvc.perform(
            jsonReq(
                put("/api/admin/users/4/status"),
                admin,
                json(Map.of("status", "DISABLED", "reason", "leaving"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
    mvc.perform(get("/api/employees/11").header("Authorization", "Bearer " + staff4b))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("username", "david.martinez@company.com", "password", PASSWORD))))
        .andExpect(status().isUnauthorized());
    mvc.perform(jsonReq(put("/api/admin/users/4/status"), admin, json(Map.of("status", "ACTIVE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.locked").value(false))
        .andExpect(jsonPath("$.failedAttempts").value(0));
    token("david.martinez@company.com");

    Map<String, Object> audit =
        jdbc.queryForMap(
            "select changed_by, count(*) as n from audit_log where table_name in"
                + " ('USER_ROLES','USER_ACCOUNTS') and record_id = '4' group by changed_by");
    assertThat(audit.get("changed_by")).isEqualTo("1");

    // read-side: paging + filters for ADMIN:VIEW
    MvcResult page =
        mvc.perform(
                get("/api/admin/users?q=martinez&page=0&size=10")
                    .header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode p = body(page);
    assertThat(p.get("content")).hasSize(1);
    assertThat(p.get("content").get(0).get("userId").asLong()).isEqualTo(4);
    assertThat(p.get("page").get("totalElements").asInt()).isEqualTo(1);
    mvc.perform(get("/api/admin/users?roleId=2").header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.content.length()").value(2));
    mvc.perform(get("/api/admin/users?size=0").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/users/4").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.empNumber").isNotEmpty())
        .andExpect(jsonPath("$.username").value("david.martinez@company.com"));
  }

  @Test
  void callerCannotGrantRolesHoldingAuthoritiesTheyLack() throws Exception {
    // Give MANAGER (user 3) ADMIN:EDIT via a custom role so they can write, but they still lack
    // PAYROLL:EDIT etc. Granting EXECUTIVE (role 3) to someone must be refused with -20806.
    MvcResult r =
        mvc.perform(
                jsonReq(
                    post("/api/admin/roles"),
                    admin,
                    json(role("USER_ADMIN", List.of("ADMIN:VIEW", "ADMIN:EDIT")))))
            .andExpect(status().isCreated())
            .andReturn();
    int userAdminRole = body(r).get("roleId").asInt();
    mvc.perform(
            jsonReq(
                put("/api/admin/users/3/roles"),
                admin,
                json(Map.of("roleIds", List.of(2, userAdminRole)))))
        .andExpect(status().isOk());
    String limitedAdmin = token(MANAGER_EMAIL);
    mvc.perform(
            jsonReq(
                put("/api/admin/users/4/roles"), limitedAdmin, json(Map.of("roleIds", List.of(3)))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20806"));
    // granting what they already hold themselves is fine
    mvc.perform(
            jsonReq(
                put("/api/admin/users/4/roles"), limitedAdmin, json(Map.of("roleIds", List.of(2)))))
        .andExpect(status().isOk());
    // and they may not create a role carrying authorities they lack
    mvc.perform(
            jsonReq(
                post("/api/admin/roles"),
                limitedAdmin,
                json(role("SNEAKY", List.of("ADMIN:EDIT", "PAYROLL:APPROVE")))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20806"));
  }

  @Test
  void lastActiveAdminEditHolderIsProtectedEvenUnderConcurrency() throws Exception {
    // Second ADMIN:EDIT holder: promote user 3 to EXECUTIVE so two holders exist.
    mvc.perform(
            jsonReq(put("/api/admin/users/3/roles"), admin, json(Map.of("roleIds", List.of(3)))))
        .andExpect(status().isOk());
    String admin3 = token(MANAGER_EMAIL);

    // Concurrently: user 1 demotes user 3 while user 3 disables user 1. Exactly one may succeed.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      results.add(
          pool.submit(
              () -> {
                go.await();
                return mvc.perform(
                        jsonReq(
                            put("/api/admin/users/3/roles"),
                            admin,
                            json(Map.of("roleIds", List.of(2)))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
      results.add(
          pool.submit(
              () -> {
                go.await();
                return mvc.perform(
                        jsonReq(
                            put("/api/admin/users/1/status"),
                            admin3,
                            json(Map.of("status", "DISABLED"))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : results) {
        statuses.add(f.get(60, TimeUnit.SECONDS));
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 422);
    } finally {
      pool.shutdownNow();
    }
    Integer holders =
        jdbc.queryForObject(
            "select count(distinct ua.user_id) from user_accounts ua"
                + " join user_roles ur on ur.user_id = ua.user_id"
                + " join role_permissions rp on rp.role_id = ur.role_id"
                + " where ua.status = 'ACTIVE' and rp.authority = 'ADMIN:EDIT'",
            Integer.class);
    assertThat(holders).isEqualTo(1);
  }

  @Test
  void removingAdminEditFromTheOnlyHoldersRoleIsRefused() throws Exception {
    // user 1 is the only ADMIN:EDIT holder through seeded EXECUTIVE; move user 5 onto a custom
    // admin role, then make user 5 the only holder by moving user 1... self is refused, so instead
    // build the state directly: user 5 sole holder via custom role, acting as user 5.
    MvcResult r =
        mvc.perform(
                jsonReq(
                    post("/api/admin/roles"),
                    admin,
                    json(role("SOLE_ADMIN", List.of("ADMIN:VIEW", "ADMIN:EDIT")))))
            .andExpect(status().isCreated())
            .andReturn();
    int sole = body(r).get("roleId").asInt();
    mvc.perform(
            jsonReq(
                put("/api/admin/users/5/roles"), admin, json(Map.of("roleIds", List.of(1, sole)))))
        .andExpect(status().isOk());
    jdbc.update("update user_accounts set must_change_password = false where user_id = 5");
    String user5 = token("emily.johnson@company.com");
    // user 5 demotes user 1 to MANAGER: allowed, user 5 remains a holder
    mvc.perform(
            jsonReq(put("/api/admin/users/1/roles"), user5, json(Map.of("roleIds", List.of(2)))))
        .andExpect(status().isOk());
    // now stripping ADMIN:EDIT from SOLE_ADMIN would leave no active holder -> -20805
    mvc.perform(
            jsonReq(
                put("/api/admin/roles/" + sole),
                user5,
                json(role("SOLE_ADMIN", List.of("ADMIN:VIEW")))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20805"));
    // user 1 (now MANAGER, no ADMIN:EDIT) cannot write at all
    String demoted = token(EXEC_EMAIL);
    mvc.perform(
            jsonReq(put("/api/admin/users/5/status"), demoted, json(Map.of("status", "DISABLED"))))
        .andExpect(status().isForbidden());
    // the sole holder cannot disable themself either (-20804) – there is no write path left that
    // could drop the holder count to zero
    mvc.perform(
            jsonReq(put("/api/admin/users/5/status"), user5, json(Map.of("status", "DISABLED"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20804"));
    // user 5 cannot hand EXECUTIVE back (least privilege: user 5 lacks e.g. PAYROLL:EDIT) ...
    mvc.perform(
            jsonReq(put("/api/admin/users/1/roles"), user5, json(Map.of("roleIds", List.of(3)))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20806"))
        .andExpect(jsonPath("$.field").value("roleIds"));
    // ... but may share its own SOLE_ADMIN role, after which user 5 is no longer the last holder
    mvc.perform(
            jsonReq(put("/api/admin/users/1/roles"), user5, json(Map.of("roleIds", List.of(sole)))))
        .andExpect(status().isOk());
    String restored = token(EXEC_EMAIL);
    mvc.perform(
            jsonReq(put("/api/admin/users/5/status"), restored, json(Map.of("status", "DISABLED"))))
        .andExpect(status().isOk()); // user 1 remains a holder, so allowed
    // with user 5 disabled, user 1 is the last active holder again
    mvc.perform(
            jsonReq(put("/api/admin/users/1/status"), user5, json(Map.of("status", "DISABLED"))))
        .andExpect(status().isUnauthorized()); // user 5's tokens were revoked by the status change
    mvc.perform(
            jsonReq(put("/api/admin/users/5/status"), restored, json(Map.of("status", "ACTIVE"))))
        .andExpect(status().isOk());
    mvc.perform(
            jsonReq(
                put("/api/admin/users/1/roles"),
                token("emily.johnson@company.com"),
                json(Map.of("roleIds", List.of(1)))))
        .andExpect(status().isOk());
    // user 5 is again the only holder; the DB-level count is 1
    assertThat(
            jdbc.queryForObject(
                "select count(distinct ua.user_id) from user_accounts ua"
                    + " join user_roles ur on ur.user_id = ua.user_id"
                    + " join role_permissions rp on rp.role_id = ur.role_id"
                    + " where ua.status = 'ACTIVE' and rp.authority = 'ADMIN:EDIT'",
                Integer.class))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------ helpers

  private static Map<String, Object> role(String code, List<String> permissions) {
    Map<String, Object> m = new HashMap<>();
    m.put("roleCode", code);
    m.put("roleName", code + " role");
    m.put("minGrade", 1);
    m.put("maxGrade", 10);
    m.put("permissions", permissions);
    return m;
  }

  private static Map<String, Object> fromRole(JsonNode r) {
    Map<String, Object> m = new HashMap<>();
    m.put("roleCode", r.get("roleCode").asText());
    m.put("roleName", r.get("roleName").asText());
    m.put("minGrade", r.get("minGrade").asInt());
    m.put("maxGrade", r.get("maxGrade").asInt());
    List<String> perms = new ArrayList<>();
    r.get("permissions").forEach(p -> perms.add(p.asText()));
    m.put("permissions", perms);
    return m;
  }

  private static MockHttpServletRequestBuilder jsonReq(
      MockHttpServletRequestBuilder b, String token, String body) {
    return b.header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private String json(Map<String, ?> m) throws Exception {
    return json.writeValueAsString(m);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * PUT /users/{id}/roles is a full replacement. A competing writer holding the admin guard grants
   * an extra role; the API call must wait for it and compute its delta from the committed state, so
   * the extra role is removed rather than silently surviving a stale read.
   */
  @Test
  void concurrentRoleGrantIsReplacedNotLeakedByStaleDelta() throws Exception {
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("select pg_advisory_xact_lock(hashtext('hrms.admin_edit_guard'))");
        st.execute(
            "insert into user_roles (user_id, role_id, granted_by, granted_date)"
                + " values (5, 2, 't', now())");
      }
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        Future<MvcResult> apiReplace =
            pool.submit(
                () ->
                    mvc.perform(
                            jsonReq(
                                put("/api/admin/users/5/roles"),
                                admin,
                                json(Map.of("roleIds", List.of(1)))))
                        .andReturn());
        assertThatThrownBy(() -> apiReplace.get(1500, TimeUnit.MILLISECONDS))
            .as("role replacement must wait for the admin guard holder")
            .isInstanceOf(TimeoutException.class);
        c.commit();
        MvcResult r = apiReplace.get(30, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("sessionsRevoked").asInt()).isGreaterThanOrEqualTo(0);
      } finally {
        pool.shutdownNow();
      }
    }
    assertThat(
            jdbc.queryForList(
                "select role_id from user_roles where user_id = 5 order by role_id", Integer.class))
        .containsExactly(1);
  }

  private String token(String email) {
    try {
      MvcResult r =
          mvc.perform(
                  post("/api/auth/login")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
              .andReturn();
      if (r.getResponse().getStatus() != 200) {
        return null;
      }
      return body(r).get("accessToken").asText();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
