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

import com.acme.hrms.admin.TaxBracketService;
import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * P5 §9 admin maintenance of HOLIDAYS / PAY_ELEMENTS / TAX_BRACKETS on PostgreSQL: authorization,
 * strict request order (malformed → unknown → Bean Validation), audit actor = jwt.sub, active
 * holiday uniqueness (Java pre-check and V12 partial index), reserved pay elements and employee
 * dependants, tax-year lock, half-open overlap, ladder gaps, and write visibility through the
 * uncached domain readers and the ETag'd /api/reference facade.
 */
class AdminPayrollReferenceApiTest extends AuthApiTestBase {

  private String admin;
  private String viewer;
  private String staff;

  @BeforeEach
  void reset() {
    HrmsPostgres.resetSchema();
    seedAccounts();
    admin = token(EXEC_EMAIL);
    viewer = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  // ---------------------------------------------------------------- authorization / strictness

  @Test
  void readsNeedAdminViewAndWritesNeedAdminEdit() throws Exception {
    mvc.perform(get("/api/admin/holidays")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/holidays").header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/pay-elements").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/tax-brackets").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk());
    mvc.perform(jsonReq(post("/api/admin/holidays"), viewer, json(holiday("2030-03-03", null))))
        .andExpect(status().isForbidden());
    mvc.perform(jsonReq(delete("/api/admin/pay-elements/205"), viewer, ""))
        .andExpect(status().isForbidden());
    mvc.perform(jsonReq(put("/api/admin/tax-brackets/9029"), viewer, json(stateBracket("CA"))))
        .andExpect(status().isForbidden());
    // authorization before body inspection: a garbage body still yields 403 for a viewer
    mvc.perform(jsonReq(post("/api/admin/pay-elements"), viewer, "{\"nope\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void malformedDecimalWireTypeIs400BeforeBeanValidation() throws Exception {
    // defaultAmount as a JSON number instead of a string: malformed, even though elementName
    // (required) is missing – the malformed check wins and lists only the wire-type problem.
    String body =
        "{\"elementCode\":\"BONUS_X\",\"elementType\":\"EARNING\","
            + "\"calculationType\":\"FLAT\",\"defaultAmount\":12.5}";
    MvcResult r =
        mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn();
    assertThat(body(r).path("details").toString()).contains("defaultAmount");
    assertThat(body(r).path("details").toString()).doesNotContain("elementName");

    String tax =
        "{\"taxYear\":2031,\"filingStatus\":\"SINGLE\",\"bracketMin\":0,"
            + "\"bracketMax\":\"10000.00\",\"taxRate\":\"0.1000\",\"baseTax\":\"0.00\"}";
    mvc.perform(jsonReq(post("/api/admin/tax-brackets"), admin, tax))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from tax_brackets where tax_year = 2031", Integer.class))
        .isZero();
  }

  @Test
  void unknownPropertyIs400BeforeBeanValidation() throws Exception {
    Map<String, Object> h = holiday("2030-03-03", null);
    h.put("holidayName", ""); // would fail @NotBlank
    h.put("observedDate", "2030-03-03"); // read-only response field, unknown on the request
    MvcResult r =
        mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(h)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn();
    assertThat(body(r).path("details").toString()).contains("observedDate");
    assertThat(body(r).path("details").toString()).doesNotContain("holidayName");
  }

  @Test
  void beanValidationRunsLast() throws Exception {
    Map<String, Object> h = holiday("2030-03-03", null);
    h.put("holidayName", "");
    MvcResult r =
        mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(h)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn();
    assertThat(body(r).path("details").toString()).contains("holidayName");
  }

  // ---------------------------------------------------------------------------------- holidays

  @Test
  void holidayLifecycleAuditsJwtSubAndAppliesObservedDate() throws Exception {
    // 2030-03-02 is a Saturday -> observed Friday 2030-03-01; 2030-03-03 Sunday -> Monday
    MvcResult created =
        mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-03-02", null))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.observedDate").value("2030-03-01"))
            .andExpect(jsonPath("$.locationCode").doesNotExist())
            .andExpect(jsonPath("$.createdBy").value("1"))
            .andExpect(jsonPath("$.modifiedBy").doesNotExist())
            .andReturn();
    int id = body(created).get("holidayId").asInt();
    assertThat(created.getResponse().getHeader("Location")).endsWith("/api/admin/holidays/" + id);

    Map<String, Object> upd = holiday("2030-03-03", "CHI");
    mvc.perform(jsonReq(put("/api/admin/holidays/" + id), admin, json(upd)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observedDate").value("2030-03-04"))
        .andExpect(jsonPath("$.locationCode").value("CHI"));

    mvc.perform(get("/api/admin/holidays/" + id).header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holidayDate").value("2030-03-03"));

    mvc.perform(jsonReq(delete("/api/admin/holidays/" + id), admin, ""))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select active_flag from holidays where holiday_id = ?", String.class, id))
        .isEqualTo("N");
    mvc.perform(
            get("/api/admin/holidays?active=true&year=2030")
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.holidayId == " + id + ")]").doesNotExist());
    mvc.perform(
            get("/api/admin/holidays?active=false&year=2030")
                .header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$[?(@.holidayId == " + id + ")]").exists());

    // audit actor is the P0 JWT sub (user-account id 1), never the employee id or the username
    List<String> actors =
        jdbc.queryForList(
            "select distinct changed_by from audit_log where table_name = 'HOLIDAYS'"
                + " and record_id = cast(? as bigint)",
            String.class,
            String.valueOf(id));
    assertThat(actors).containsExactly("1");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where table_name = 'HOLIDAYS'"
                    + " and record_id = cast(? as bigint)",
                Integer.class,
                String.valueOf(id)))
        .isEqualTo(3);
  }

  @Test
  void activeHolidayUniquenessPerDateAndLocationInJavaAndInV12Index() throws Exception {
    mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-07-04", null))))
        .andExpect(status().isCreated());
    // same date company-wide again -> 409 -20601
    mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-07-04", null))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"));
    // same date for a location is a different key
    MvcResult chi =
        mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-07-04", "CHI"))))
            .andExpect(status().isCreated())
            .andReturn();
    int chiId = body(chi).get("holidayId").asInt();
    mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-07-04", "CHI"))))
        .andExpect(status().isConflict());
    // deactivated rows do not participate
    mvc.perform(jsonReq(delete("/api/admin/holidays/" + chiId), admin, ""))
        .andExpect(status().isNoContent());
    mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-07-04", "CHI"))))
        .andExpect(status().isCreated());

    // the forward-migration index is the concurrency backstop for the Java pre-check
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uk_holidays_active_date_loc'",
                Integer.class))
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into holidays (holiday_id, holiday_name, holiday_date, location_code,"
                        + " active_flag, created_by, created_date)"
                        + " values (nextval('seq_holiday'), 'dup', date '2030-07-04', null, 'Y',"
                        + " 'x', now())"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    // unknown / inactive location -> 400 -20604
    mvc.perform(jsonReq(post("/api/admin/holidays"), admin, json(holiday("2030-08-08", "ZZZ"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("-20604"))
        .andExpect(jsonPath("$.field").value("locationCode"));
  }

  // ------------------------------------------------------------------------------ pay elements

  @Test
  void payElementReservedRowsAndTaxCreationAreProtected() throws Exception {
    Map<String, Object> tax = payElement("NEW_TAX", "TAX", "FORMULA");
    mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, json(tax)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20607"));

    for (long reserved : new long[] {0, 1, 100, 101, 102, 103}) {
      mvc.perform(jsonReq(delete("/api/admin/pay-elements/" + reserved), admin, ""))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("-20607"));
    }

    // BASE_PAY: name / GL / priority may change, calculation type may not
    MvcResult base =
        mvc.perform(get("/api/admin/pay-elements/1").header("Authorization", "Bearer " + admin))
            .andReturn();
    Map<String, Object> req = asRequest(body(base));
    req.put("defaultAmount", "0.00"); // seeded FLAT rows carry null; the frozen DTO needs >= 0
    req.put("elementName", "Base Salary (renamed)");
    req.put("glAccountCode", "5100-101");
    req.put("priorityOrder", 2);
    mvc.perform(jsonReq(put("/api/admin/pay-elements/1"), admin, json(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.elementName").value("Base Salary (renamed)"))
        .andExpect(jsonPath("$.priorityOrder").value(2));
    req.put("calculationType", "HOURS");
    req.put("defaultAmount", "10.00");
    mvc.perform(jsonReq(put("/api/admin/pay-elements/1"), admin, json(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20607"));

    // ERROR sentinel row 0 is fully immutable: its elementType is outside the frozen DTO's
    // vocabulary, so no PUT body can even reach the service, and any other type is -20607
    MvcResult err =
        mvc.perform(get("/api/admin/pay-elements/0").header("Authorization", "Bearer " + admin))
            .andExpect(jsonPath("$.elementType").value("ERROR"))
            .andReturn();
    Map<String, Object> errReq = asRequest(body(err));
    errReq.put("elementName", "renamed sentinel");
    mvc.perform(jsonReq(put("/api/admin/pay-elements/0"), admin, json(errReq)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    errReq.put("elementType", "EARNING");
    errReq.put("defaultAmount", "0.00");
    mvc.perform(jsonReq(put("/api/admin/pay-elements/0"), admin, json(errReq)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20607"))
        .andExpect(jsonPath("$.field").value("elementType"));
  }

  @Test
  void payElementCalculationRulesDependantsAndOrdering() throws Exception {
    Map<String, Object> pct = payElement("UNION_DUES", "DEDUCTION", "PERCENTAGE");
    pct.put("defaultPercentage", "150.00"); // > 100 -> Bean Validation
    mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, json(pct)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    pct.put("defaultPercentage", null);
    pct.put("defaultAmount", "10.00"); // PERCENTAGE needs a percentage, not an amount
    // the frozen DTO carries the calculationType x defaults rule as an @AssertTrue constraint
    mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, json(pct)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("defaultsConsistentWithCalculationType"));
    pct.put("defaultAmount", null);
    pct.put("defaultPercentage", "1.50");
    pct.put("pretaxFlag", true);
    MvcResult created =
        mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, json(pct)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.defaultPercentage").value("1.50"))
            .andExpect(jsonPath("$.createdBy").value("1"))
            .andReturn();
    long id = body(created).get("elementId").asLong();
    assertThat(id).isGreaterThan(205); // seq_pay_element advanced past the fixture

    // pretax on an earning is a value rule violation (frozen DTO @AssertTrue)
    Map<String, Object> earn = payElement("SPOT_BONUS", "EARNING", "FLAT");
    earn.put("defaultAmount", "100.00");
    earn.put("pretaxFlag", true);
    mvc.perform(jsonReq(post("/api/admin/pay-elements"), admin, json(earn)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("pretaxOnlyOnDeduction"));

    // element code is immutable on PUT
    Map<String, Object> upd = asRequest(body(created));
    upd.put("elementCode", "UNION_DUES2");
    mvc.perform(jsonReq(put("/api/admin/pay-elements/" + id), admin, json(upd)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20601"));

    // an active, non-ended employee pay element blocks deactivation with -20602
    jdbc.update(
        "insert into employee_pay_elements (emp_element_id, emp_id, element_id, amount,"
            + " effective_date, active_flag, created_by, created_date)"
            + " values (nextval('seq_emp_pay_element'), 2, ?, 5, date '2024-01-01', 'Y', 't', now())",
        id);
    mvc.perform(jsonReq(delete("/api/admin/pay-elements/" + id), admin, ""))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20602"));
    jdbc.update(
        "update employee_pay_elements set end_date = date '2024-02-01' where element_id = ?", id);
    mvc.perform(jsonReq(delete("/api/admin/pay-elements/" + id), admin, ""))
        .andExpect(status().isNoContent());

    MvcResult list =
        mvc.perform(
                get("/api/admin/pay-elements?active=true")
                    .header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arr = body(list);
    int prev = Integer.MIN_VALUE;
    for (JsonNode n : arr) {
      assertThat(n.get("activeFlag").asBoolean()).isTrue();
      assertThat(n.get("priorityOrder").asInt()).isGreaterThanOrEqualTo(prev);
      prev = n.get("priorityOrder").asInt();
    }
    assertThat(arr.toString()).doesNotContain("UNION_DUES");
  }

  // ------------------------------------------------------------------------------ tax brackets

  @Test
  void lockedTaxYearRejectsWritesAndOpenYearAcceptsLadder() throws Exception {
    // run 9001 is APPROVED for MAY-2024 -> 2024 is locked
    Map<String, Object> b2024 =
        federalBracket(2024, "SINGLE", "0.00", "10000.00", "0.1000", "0.00");
    mvc.perform(jsonReq(post("/api/admin/tax-brackets"), admin, json(b2024)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20609"));
    mvc.perform(jsonReq(delete("/api/admin/tax-brackets/9001"), admin, ""))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20609"));
    mvc.perform(get("/api/admin/tax-brackets/9001").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locked").value(true))
        .andExpect(jsonPath("$.stateCode").doesNotExist())
        .andExpect(jsonPath("$.taxRate").value("0.1000"));

    // 2031 has no approved runs: build a two-step ladder with a gap
    MvcResult low =
        mvc.perform(
                jsonReq(
                    post("/api/admin/tax-brackets"),
                    admin,
                    json(federalBracket(2031, "SINGLE", "0.00", "10000.00", "0.1000", "0.00"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locked").value(false))
            .andExpect(jsonPath("$.createdBy").value("1"))
            .andReturn();
    long lowId = body(low).get("bracketId").asLong();
    mvc.perform(
            jsonReq(
                post("/api/admin/tax-brackets"),
                admin,
                json(federalBracket(2031, "SINGLE", "12000.00", null, "0.2000", "1000.00"))))
        .andExpect(status().isCreated());

    // overlapping half-open range -> 409 -20608 ([9000, 13000) touches both)
    mvc.perform(
            jsonReq(
                post("/api/admin/tax-brackets"),
                admin,
                json(federalBracket(2031, "SINGLE", "9000.00", "13000.00", "0.1500", "0.00"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("-20608"));
    // adjacency is not overlap: [10000, 12000) exactly plugs the gap for a different status
    mvc.perform(
            jsonReq(
                post("/api/admin/tax-brackets"),
                admin,
                json(federalBracket(2031, "MARRIED_JOINT", "0.00", null, "0.1000", "0.00"))))
        .andExpect(status().isCreated());

    MvcResult gaps =
        mvc.perform(
                get("/api/admin/tax-brackets/ladder-gaps?taxYear=2031")
                    .header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode g = body(gaps);
    assertThat(g.isArray()).isTrue();
    JsonNode single = null;
    for (JsonNode n : g) {
      if ("SINGLE".equals(n.get("filingStatus").asText())) {
        single = n;
      }
    }
    assertThat(single).isNotNull();
    assertThat(single.get("gaps")).hasSize(1);
    assertThat(single.get("gaps").get(0).get("from").asText()).isEqualTo("10000.00");
    assertThat(single.get("gaps").get(0).get("to").asText()).isEqualTo("12000.00");

    // plug the gap; ladder reports none for SINGLE afterwards
    mvc.perform(
            jsonReq(
                post("/api/admin/tax-brackets"),
                admin,
                json(federalBracket(2031, "SINGLE", "10000.00", "12000.00", "0.1200", "1000.00"))))
        .andExpect(status().isCreated());
    MvcResult gaps2 =
        mvc.perform(
                get("/api/admin/tax-brackets/ladder-gaps?taxYear=2031")
                    .header("Authorization", "Bearer " + viewer))
            .andReturn();
    for (JsonNode n : body(gaps2)) {
      if ("SINGLE".equals(n.get("filingStatus").asText())) {
        assertThat(n.get("gaps")).isEmpty();
      }
    }

    // deactivate then a replacement in the same range is allowed (inactive rows don't overlap)
    mvc.perform(jsonReq(delete("/api/admin/tax-brackets/" + lowId), admin, ""))
        .andExpect(status().isNoContent());
    mvc.perform(
            jsonReq(
                post("/api/admin/tax-brackets"),
                admin,
                json(federalBracket(2031, "SINGLE", "0.00", "10000.00", "0.1100", "0.00"))))
        .andExpect(status().isCreated());

    // state rows: two-letter code, ALL, [0, +inf); the frozen DTO's @AssertTrue shape rule answers
    Map<String, Object> bad = stateBracket("CA");
    bad.put("taxYear", 2031);
    bad.put("filingStatus", "SINGLE");
    mvc.perform(jsonReq(post("/api/admin/tax-brackets"), admin, json(bad)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].field").value("stateRowShape"));
    Map<String, Object> ca = stateBracket("CA");
    ca.put("taxYear", 2031);
    mvc.perform(jsonReq(post("/api/admin/tax-brackets"), admin, json(ca)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.bracketMax").doesNotExist());

    MvcResult list =
        mvc.perform(
                get("/api/admin/tax-brackets?taxYear=2031&active=true")
                    .header("Authorization", "Bearer " + viewer))
            .andReturn();
    JsonNode arr = body(list);
    // federal (null state) first, then filing status, then bracket min
    assertThat(arr.get(0).get("stateCode").isNull() || arr.get(0).get("stateCode").isMissingNode())
        .isTrue();
    assertThat(arr.get(arr.size() - 1).get("stateCode").asText()).isEqualTo("CA");
  }

  // ----------------------------------------------------------- write visibility / facade ETag

  @Test
  void referenceFacadeStaysAuthenticatedReadOnlyWithFreshEtagAfterAdminWrite() throws Exception {
    mvc.perform(get("/api/reference/locations")).andExpect(status().isUnauthorized());
    MvcResult before =
        mvc.perform(get("/api/reference/locations").header("Authorization", "Bearer " + staff))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, private"))
            .andExpect(header().exists("ETag"))
            .andReturn();
    String etag = before.getResponse().getHeader("ETag");
    mvc.perform(
            get("/api/reference/locations")
                .header("Authorization", "Bearer " + staff)
                .header("If-None-Match", etag))
        .andExpect(status().isNotModified());

    // admin write through the admin-owned writer; the staff facade sees it on the next request
    mvc.perform(
            jsonReq(
                post("/api/admin/locations"),
                admin,
                json(
                    Map.of(
                        "locationCode", "P5X",
                        "locationName", "P5 Test Site",
                        "city", "Austin",
                        "stateProvince", "TX",
                        "countryCode", "US",
                        "timezone", "America/Chicago"))))
        .andExpect(status().isCreated());
    MvcResult after =
        mvc.perform(
                get("/api/reference/locations")
                    .header("Authorization", "Bearer " + staff)
                    .header("If-None-Match", etag))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(etag);
    assertThat(after.getResponse().getContentAsString()).contains("P5X");
    // facade stays read-only: no write verbs served
    mvc.perform(jsonReq(post("/api/reference/locations"), admin, "{}"))
        .andExpect(status().isMethodNotAllowed());
    // new domain readers are not part of the cached facade
    mvc.perform(get("/api/reference/holidays").header("Authorization", "Bearer " + staff))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/holidays").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("ETag"));
  }

  // ------------------------------------------------------------------------------------ helpers

  private static Map<String, Object> holiday(String date, String location) {
    Map<String, Object> m = new HashMap<>();
    m.put("holidayDate", date);
    m.put("holidayName", "Test Holiday " + date + (location == null ? "" : " " + location));
    m.put("locationCode", location);
    m.put("floatingFlag", false);
    m.put("activeFlag", true);
    return m;
  }

  private static Map<String, Object> payElement(String code, String type, String calc) {
    Map<String, Object> m = new HashMap<>();
    m.put("elementCode", code);
    m.put("elementName", code + " name");
    m.put("elementType", type);
    m.put("calculationType", calc);
    m.put("taxableFlag", "EARNING".equals(type));
    m.put("pretaxFlag", false);
    m.put("employerPaid", false);
    m.put("glAccountCode", "9999-001");
    m.put("priorityOrder", 50);
    m.put("activeFlag", true);
    return m;
  }

  private static Map<String, Object> federalBracket(
      int year, String status, String min, String max, String rate, String base) {
    Map<String, Object> m = new HashMap<>();
    m.put("taxYear", year);
    m.put("filingStatus", status);
    m.put("stateCode", null);
    m.put("bracketMin", min);
    m.put("bracketMax", max);
    m.put("taxRate", rate);
    m.put("baseTax", base);
    m.put("activeFlag", true);
    return m;
  }

  private static Map<String, Object> stateBracket(String state) {
    Map<String, Object> m = federalBracket(2024, "ALL", "0.00", null, "0.0500", "0.00");
    m.put("stateCode", state);
    return m;
  }

  /** Response -> request body (drops read-only fields). */
  /**
   * A competing writer that already holds the ladder lock and inserts an overlapping bracket must
   * make a concurrent API create wait and then fail with -20608 instead of committing a second,
   * overlapping row.
   */
  @Test
  void concurrentOverlappingTaxBracketInsertsSerializePerLadder() throws Exception {
    String key = TaxBracketService.ladderKey(2031, "SINGLE", null);
    try (Connection c = HrmsPostgres.dataSource().getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("select pg_advisory_xact_lock(hashtext('" + key + "'))");
        st.execute(
            "insert into tax_brackets (bracket_id, tax_year, filing_status, state_code,"
                + " bracket_min, bracket_max, tax_rate, base_tax, active_flag, created_by,"
                + " created_date) values (nextval('seq_tax_bracket'), 2031, 'SINGLE', null,"
                + " 5000, 20000, 0.1, 0, 'Y', 't', now())");
      }
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        Future<MvcResult> apiCreate =
            pool.submit(
                () ->
                    mvc.perform(
                            jsonReq(
                                post("/api/admin/tax-brackets"),
                                admin,
                                json(
                                    federalBracket(
                                        2031, "SINGLE", "0.00", "10000.00", "0.1000", "0.00"))))
                        .andReturn());
        assertThatThrownBy(() -> apiCreate.get(1500, TimeUnit.MILLISECONDS))
            .as("API create must wait for the ladder lock holder")
            .isInstanceOf(TimeoutException.class);
        c.commit();
        MvcResult r = apiCreate.get(30, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).get("code").asText()).isEqualTo("-20608");
      } finally {
        pool.shutdownNow();
      }
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from tax_brackets where tax_year = 2031 and active_flag = 'Y'",
                Integer.class))
        .isEqualTo(1);
  }

  private static Map<String, Object> asRequest(JsonNode pe) {
    Map<String, Object> m = new HashMap<>();
    for (String f :
        List.of(
            "elementCode",
            "elementName",
            "elementType",
            "calculationType",
            "defaultAmount",
            "defaultPercentage",
            "taxableFlag",
            "pretaxFlag",
            "employerPaid",
            "glAccountCode",
            "priorityOrder",
            "activeFlag")) {
      JsonNode v = pe.get(f);
      if (v == null || v.isNull()) {
        m.put(f, null);
      } else if (v.isBoolean()) {
        m.put(f, v.asBoolean());
      } else if (v.isInt()) {
        m.put(f, v.asInt());
      } else {
        m.put(f, v.asText());
      }
    }
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

  private String token(String email) {
    try {
      MvcResult r =
          mvc.perform(
                  post("/api/auth/login")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
              .andExpect(status().isOk())
              .andReturn();
      return body(r).get("accessToken").asText();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
