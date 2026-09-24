package com.acme.hrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Level 1 for the integration module (PKG_INTEGRATION replacement): GL / benefits feed layouts
 * against the committed golden files in tests/golden/feeds, time-attendance staging, object-storage
 * metadata, per-feed download authorization and the frozen error codes.
 */
class IntegrationApiTest extends AuthApiTestBase {

  private static final Path FEEDS = HrmsPostgres.repoRoot().resolve("tests/golden/feeds");

  /** JWT {@code sub} of the EXECUTIVE account (user 1) – the actor recorded on every write. */
  private static final String EXEC_USER = "1";

  private String exec;
  private String manager;
  private String staff;

  @BeforeEach
  void seed() throws Exception {
    HrmsPostgres.resetSchema();
    seedAccounts();
    exec = token(EXEC_EMAIL);
    manager = token(MANAGER_EMAIL);
    staff = token(STAFF_EMAIL);
  }

  @Test
  void glFeedMatchesGoldenLayoutAndIsVersionedPerRun() throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/integration/gl-feed")
                    .header("Authorization", "Bearer " + exec)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"runId\":9001}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.feed").value("GL_JOURNAL"))
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.createdBy").value(EXEC_USER))
            .andReturn();
    JsonNode f = body(r);
    String fileId = f.get("fileId").asText();
    assertThat(f.get("fileName").asText()).matches("GL_JOURNAL_9001_\\d{8}\\.txt");
    assertThat(f.get("storageKey").asText())
        .matches("GL_JOURNAL/\\d{4}/\\d{2}/" + fileId + "-GL_JOURNAL_9001_\\d{8}\\.txt");
    assertThat(f.get("contentUrl").asText())
        .isEqualTo("/api/integration/files/" + fileId + "/content");
    assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/integration/files/" + fileId);

    String content = download(fileId, exec, f.get("sha256").asText(), "GL_JOURNAL_9001_");
    assertGolden("gl_journal_9001.txt", content);
    List<String> lines = content.lines().toList();
    assertThat(lines.get(0)).isEqualTo("H|HRMS_PAYROLL|2024-05-31|9001");
    assertThat(lines.get(lines.size() - 1)).isEqualTo("T|" + (lines.size() - 2));
    assertThat(lines.subList(1, lines.size() - 1))
        .allMatch(
            l ->
                l.matches(
                    "D\\|[^|]+\\|[^|]+\\|\\d+\\.\\d{2}\\|\\d+\\.\\d{2}\\|Payroll [^|]+\\|RUN-9001"));
    assertThat(f.get("recordCount").asInt()).isEqualTo(lines.size() - 2);

    // rerun = new version, both files kept, both logged
    JsonNode again =
        body(
            mvc.perform(
                    post("/api/integration/gl-feed")
                        .header("Authorization", "Bearer " + exec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":9001}"))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(again.get("fileId").asText()).isNotEqualTo(fileId);
    assertThat(again.get("sha256").asText()).isEqualTo(f.get("sha256").asText());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from integration_log where feed='GL_JOURNAL' and status='SUCCESS'"
                    + " and created_by=?",
                Integer.class,
                EXEC_USER))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from integration_files where feed='GL_JOURNAL' and source_ref='9001'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void glFeedRefusesNonApprovedRunsAndUnknownRuns() throws Exception {
    mvc.perform(
            post("/api/integration/gl-feed")
                .header("Authorization", "Bearer " + exec)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":9002}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20701"))
        .andExpect(jsonPath("$.field").value("runId"))
        .andExpect(
            jsonPath("$.message").value("Cannot export GL feed for run in status: CALCULATED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from integration_log where feed='GL_JOURNAL' and status='FAILED'",
                Integer.class))
        .isEqualTo(1);
    mvc.perform(
            post("/api/integration/gl-feed")
                .header("Authorization", "Bearer " + exec)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":4242}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    // PAYROLL:APPROVE required – MANAGER only has VIEW
    mvc.perform(
            post("/api/integration/gl-feed")
                .header("Authorization", "Bearer " + manager)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":9001}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            post("/api/integration/gl-feed")
                .header("Authorization", "Bearer " + exec)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void benefitsFeedMatchesGoldenLayoutAndMasksSsn() throws Exception {
    JsonNode f =
        body(
            mvc.perform(
                    post("/api/integration/benefits-feed")
                        .header("Authorization", "Bearer " + exec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveDate\":\"2024-06-30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feed").value("BENEFITS_FEED"))
                .andExpect(jsonPath("$.fileName").value("BENEFITS_20240630.txt"))
                .andReturn());
    String content =
        download(f.get("fileId").asText(), exec, f.get("sha256").asText(), "BENEFITS_20240630.txt");
    assertGolden("benefits_20240630.txt", content);
    List<String> lines = content.lines().toList();
    long active =
        jdbc.queryForObject(
            "select count(*) from employees where employment_status='ACTIVE' and hire_date <= date '2024-06-30'",
            Long.class);
    assertThat(lines.stream().filter(l -> l.startsWith("E")).count()).isEqualTo(active);
    assertThat(f.get("recordCount").asInt()).isEqualTo(lines.size());
    // E record: 1+10+30+30+11+8+1+8+1+12+20+10 = 142 fixed columns
    assertThat(lines.stream().filter(l -> l.startsWith("E"))).allMatch(l -> l.length() == 142);
    // SSN column (offset 71..82) is masked; full 9-digit SSNs never appear
    for (String l : lines) {
      if (l.startsWith("E")) {
        assertThat(l.substring(71, 82)).matches("\\*\\*\\*-\\*\\*-\\d{4}|\\s{11}");
      }
    }
    assertThat(content).doesNotContainPattern("\\d{3}-\\d{2}-\\d{4}");
    // content download needs EMPLOYEE:VIEW (not PAYROLL:VIEW) besides ADMIN:VIEW – manager has both
    mvc.perform(
            get("/api/integration/files/" + f.get("fileId").asText() + "/content")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk());
    // ADMIN:EDIT required
    mvc.perform(
            post("/api/integration/benefits-feed")
                .header("Authorization", "Bearer " + manager)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void timeAttendanceImportStagesOnlyAndReportsPerLineVerdicts() throws Exception {
    String csv =
        "emp_number,date,hours_regular,hours_overtime\n"
            + "EMP-000002,2024-06-03,8,0\n"
            + "EMP-000002,2024-06-03,8,0\n" // duplicate -> -20703
            + "EMP-000099,2024-06-03,8,0\n" // unknown -> -20001
            + "EMP-000003,2024-06-03,25,0\n" // hours > 24 -> VALIDATION_FAILED
            + "EMP-000003,2024-06-04,8.5,1.25\n";
    long payrollDetails = jdbc.queryForObject("select count(*) from payroll_details", Long.class);
    long leaveBalances = jdbc.queryForObject("select count(*) from leave_balances", Long.class);
    JsonNode r =
        body(
            mvc.perform(upload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", exec))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.file.feed").value("TIME_ATTENDANCE"))
                .andExpect(jsonPath("$.file.status").value("STAGED"))
                .andExpect(jsonPath("$.applied").value(false))
                .andReturn());
    assertThat(r.get("accepted").asInt()).isEqualTo(2);
    assertThat(r.get("rejected").asInt()).isEqualTo(3);
    JsonNode lines = r.get("lines");
    assertThat(lines).hasSize(5);
    assertThat(lines.get(0).get("verdict").asText()).isEqualTo("ACCEPTED");
    assertThat(lines.get(0).get("line").asInt()).isEqualTo(2);
    assertThat(lines.get(0).get("empId").asLong()).isEqualTo(2);
    assertThat(lines.get(1).at("/error/code").asText()).isEqualTo("-20703");
    assertThat(lines.get(2).at("/error/code").asText()).isEqualTo("-20001");
    assertThat(lines.get(3).at("/error/code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(lines.get(4).get("verdict").asText()).isEqualTo("ACCEPTED");
    assertThat(lines.get(4).get("hoursOvertime").asText()).isEqualTo("1.25");
    assertThat(jdbc.queryForObject("select count(*) from payroll_details", Long.class))
        .isEqualTo(payrollDetails);
    assertThat(jdbc.queryForObject("select count(*) from leave_balances", Long.class))
        .isEqualTo(leaveBalances);
    String fileId = r.at("/file/fileId").asText();
    // staged artefact = header + the accepted, normalised lines only
    String staged = download(fileId, exec, r.at("/file/sha256").asText(), "attendance.csv");
    assertThat(staged)
        .isEqualTo(
            "emp_number,date,hours_regular,hours_overtime\n"
                + "EMP-000002,2024-06-03,8.00,0.00\n"
                + "EMP-000003,2024-06-04,8.50,1.25\n");
    assertThat(
            jdbc.queryForObject(
                "select status from integration_log where feed='TIME_ATTENDANCE'", String.class))
        .isEqualTo("STAGED");
  }

  @Test
  void timeAttendanceImportRejectsWholeFileWrongTypeAndTooLarge() throws Exception {
    String csv = "emp_number,date,hours_regular,hours_overtime\nEMP-000099,2024-06-03,8,0\n";
    mvc.perform(upload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", exec))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("-20704"))
        .andExpect(jsonPath("$.field").value("file"))
        .andExpect(jsonPath("$.details[0].code").value("-20001"))
        .andExpect(jsonPath("$.details[0].field").value("line 2"));
    mvc.perform(upload(csv.getBytes(StandardCharsets.UTF_8), "application/json", exec))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    byte[] big = new byte[5 * 1024 * 1024 + 1];
    java.util.Arrays.fill(big, (byte) 'a');
    mvc.perform(upload(big, "text/csv", exec))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    StringBuilder many = new StringBuilder("emp_number,date,hours_regular,hours_overtime\n");
    for (int i = 0; i < 50_001; i++) {
      many.append("EMP-000002,2024-01-01,1,0\n");
    }
    mvc.perform(upload(many.toString().getBytes(StandardCharsets.UTF_8), "text/csv", exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // PAYROLL:EDIT required
    mvc.perform(upload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", manager))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("select count(*) from integration_files", Long.class)).isZero();
  }

  @Test
  void timeAttendanceImportRejectsNonMultipartRequestWith415() throws Exception {
    String csv = "emp_number,date,hours_regular,hours_overtime\nEMP-000002,2024-06-03,8,0\n";
    for (MediaType outer : List.of(MediaType.valueOf("text/csv"), MediaType.APPLICATION_JSON)) {
      mvc.perform(
              post("/api/integration/time-attendance/import")
                  .header("Authorization", "Bearer " + exec)
                  .contentType(outer)
                  .content(csv))
          .andExpect(status().isUnsupportedMediaType())
          .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
          .andExpect(jsonPath("$.message").value("Expected multipart/form-data"))
          .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    assertThat(jdbc.queryForObject("select count(*) from integration_files", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from error_log where error_key = 'UNSUPPORTED_MEDIA_TYPE'"
                    + " and http_status = 415",
                Long.class))
        .isEqualTo(2);
    mvc.perform(upload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", exec))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.file.status").value("STAGED"))
        .andExpect(jsonPath("$.accepted").value(1));
  }

  @Test
  void timeAttendanceImportRejectsMissingOrMisnamedFilePartWith400() throws Exception {
    String csv = "emp_number,date,hours_regular,hours_overtime\nEMP-000002,2024-06-03,8,0\n";
    byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
    mvc.perform(
            multipart("/api/integration/time-attendance/import")
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("file"))
        .andExpect(jsonPath("$.details[0].field").value("file"))
        .andExpect(jsonPath("$.details[0].code").value("Required"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
    mvc.perform(
            multipart("/api/integration/time-attendance/import")
                .file(new MockMultipartFile("upload", "attendance.csv", "text/csv", bytes))
                .header("Authorization", "Bearer " + exec))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.field").value("file"));
    assertThat(jdbc.queryForObject("select count(*) from integration_files", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from error_log where error_key = 'VALIDATION_FAILED'"
                    + " and http_status = 400",
                Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from error_log where error_key = 'INTERNAL_ERROR'", Long.class))
        .isZero();
    mvc.perform(upload(bytes, "text/csv", exec))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.file.status").value("STAGED"));
  }

  @Test
  void filesStatusAndPerFeedDownloadAuthorization() throws Exception {
    mvc.perform(get("/api/integration/status").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[?(@.feed=='GL_JOURNAL')].status").value("NEVER_RUN"));
    JsonNode gl =
        body(
            mvc.perform(
                    post("/api/integration/gl-feed")
                        .header("Authorization", "Bearer " + exec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":9001}"))
                .andExpect(status().isCreated())
                .andReturn());
    String glId = gl.get("fileId").asText();
    mvc.perform(get("/api/integration/status").header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.feed=='GL_JOURNAL')].status").value("SUCCESS"))
        .andExpect(jsonPath("$[?(@.feed=='GL_JOURNAL')].lastFileId").value(glId))
        .andExpect(jsonPath("$[?(@.feed=='GL_JOURNAL')].lastRunBy").value(EXEC_USER));
    mvc.perform(
            get("/api/integration/files?feed=GL_JOURNAL")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].fileId").value(glId))
        .andExpect(jsonPath("$.page.totalElements").value(1));
    mvc.perform(
            get("/api/integration/files?feed=BENEFITS_FEED")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
    mvc.perform(get("/api/integration/files/" + glId).header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sha256").value(gl.get("sha256").asText()));
    // manager: ADMIN:VIEW + PAYROLL:VIEW -> may download GL
    mvc.perform(
            get("/api/integration/files/" + glId + "/content")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    // staff: no ADMIN:VIEW at all
    mvc.perform(get("/api/integration/files/" + glId).header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/integration/files/" + glId + "/content")
                .header("Authorization", "Bearer " + staff))
        .andExpect(status().isForbidden());
    // ADMIN:VIEW alone is not enough for the feed content – drop PAYROLL:VIEW from MANAGER
    jdbc.update("delete from role_permissions where role_id = 2 and authority = 'PAYROLL:VIEW'");
    String managerNoPayroll = token(MANAGER_EMAIL);
    mvc.perform(
            get("/api/integration/files/" + glId)
                .header("Authorization", "Bearer " + managerNoPayroll))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/integration/files/" + glId + "/content")
                .header("Authorization", "Bearer " + managerNoPayroll))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/api/integration/files/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    mvc.perform(
            get("/api/integration/files/00000000-0000-0000-0000-000000000000/content")
                .header("Authorization", "Bearer " + manager))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
  }

  private MockHttpServletRequestBuilder upload(byte[] bytes, String contentType, String token) {
    return multipart("/api/integration/time-attendance/import")
        .file(new MockMultipartFile("file", "attendance.csv", contentType, bytes))
        .header("Authorization", "Bearer " + token);
  }

  private String download(String fileId, String token, String sha256, String namePrefix)
      throws Exception {
    MvcResult r =
        mvc.perform(
                get("/api/integration/files/" + fileId + "/content")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"" + sha256 + "\""))
            .andReturn();
    assertThat(r.getResponse().getHeader("Content-Disposition"))
        .startsWith("attachment")
        .contains(namePrefix);
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * Golden feed files live in tests/golden/feeds and are regenerated (never hand-edited) with
   * {@code -Dhrms.golden.update=true}; see tests/golden/README.md (Phase 5) for provenance.
   */
  private static void assertGolden(String file, String actual) throws Exception {
    Path p = FEEDS.resolve(file);
    if (Boolean.getBoolean("hrms.golden.update")) {
      Files.createDirectories(FEEDS);
      Files.writeString(p, actual, StandardCharsets.UTF_8);
    }
    assertThat(p).as("golden feed file %s", p).exists();
    assertThat(actual).isEqualTo(Files.readString(p, StandardCharsets.UTF_8));
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token(String email) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("username", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    return body(r).get("accessToken").asText();
  }
}
