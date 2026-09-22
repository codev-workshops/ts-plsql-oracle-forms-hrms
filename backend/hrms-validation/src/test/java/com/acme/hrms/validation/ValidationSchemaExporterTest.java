package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hrms.validation.export.ValidationSchemaExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * TEST_STRATEGY.md §2.1 snapshot: the exporter output differs from the frozen
 * frontend/src/generated/validation-schema.json only in generatorVersion; the sourceHash (a hash
 * over parameters + dtos) must match, which pins the committed file to the annotated DTOs.
 */
class ValidationSchemaExporterTest {

  @Test
  void matchesFrozenSnapshotExceptProvenanceFields() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("frontend/src/generated/validation-schema.json"))) {
      root = root.getParent();
    }
    ObjectMapper mapper = new ObjectMapper();
    JsonNode frozen =
        mapper.readTree(root.resolve("frontend/src/generated/validation-schema.json").toFile());
    ObjectNode generated = new ValidationSchemaExporter().export("0.1.0-SNAPSHOT", 8);

    assertThat(generated.get("generatorVersion").asText())
        .isNotEqualTo(frozen.get("generatorVersion").asText());
    assertThat(generated.get("sourceHash").asText())
        .matches("[0-9a-f]{64}")
        .isEqualTo(frozen.get("sourceHash").asText());

    ObjectNode f = frozen.deepCopy();
    ObjectNode g = generated.deepCopy();
    f.remove("generatorVersion");
    g.remove("generatorVersion");
    assertThat(g).isEqualTo(f);
  }

  @Test
  void p1PerformanceDtosPinLegacyBounds() {
    JsonNode dtos = new ValidationSchemaExporter().export("x", 8).get("dtos");
    JsonNode rating = dtos.get("ManagerReviewRequest").get("fields").get("overallRating");
    assertThat(rating.get("type").asText()).isEqualTo("decimal");
    assertThat(rating.get("min").doubleValue()).isEqualTo(1.0);
    assertThat(rating.get("max").doubleValue()).isEqualTo(5.0);
    assertThat(rating.get("rules").get(0).get("errorCode").asText()).isEqualTo("-20403");
    for (String[] fp :
        new String[][] {{"GoalRequest", "weightPct"}, {"GoalProgressRequest", "progressPct"}}) {
      JsonNode pct = dtos.get(fp[0]).get("fields").get(fp[1]);
      assertThat(pct.get("min").doubleValue()).isEqualTo(0.0);
      assertThat(pct.get("max").doubleValue()).isEqualTo(100.0);
    }
    for (String dto : new String[] {"ReviewCycleRequest", "GoalRequest", "GoalProgressRequest"}) {
      assertThat(dtos.get(dto).get("module").asText()).isEqualTo("p1-performance");
    }
    assertThat(dtos.get("LoginRequest").get("module").asText()).isEqualTo("p0-foundation");
  }

  @Test
  void p2LeaveDtosPinLegacyDateRules() {
    ObjectNode root = new ValidationSchemaExporter().export("x", 8);
    assertThat(root.get("modules").get(2).asText()).isEqualTo("p2-leave");
    JsonNode dtos = root.get("dtos");
    for (String dto :
        new String[] {
          "LeaveRequestCreateRequest",
          "LeaveCancelRequest",
          "LeaveApproveRequest",
          "LeaveRejectRequest",
          "BusinessDaysQuery"
        }) {
      assertThat(dtos.get(dto).get("module").asText()).isEqualTo("p2-leave");
    }
    JsonNode create = dtos.get("LeaveRequestCreateRequest").get("fields");
    assertThat(create.get("halfDay").get("type").asText()).isEqualTo("boolean");
    assertThat(create.get("halfDayPeriod").get("values")).hasSize(2);
    JsonNode start = create.get("startDate").get("rules").get(0);
    assertThat(start.get("kind").asText()).isEqualTo("custom");
    assertThat(start.get("value").asText()).isEqualTo("5");
    assertThat(start.get("errorCode").asText()).isEqualTo("-20211");
    JsonNode end = create.get("endDate").get("rules").get(0);
    assertThat(end.get("value").asText()).isEqualTo("startDate");
    assertThat(end.get("errorCode").asText()).isEqualTo("-20210");
    assertThat(
            dtos.get("BusinessDaysQuery")
                .get("fields")
                .get("end")
                .get("rules")
                .get(0)
                .get("errorCode")
                .asText())
        .isEqualTo("-20210");
    assertThat(create.get("reason").get("maxLength").asInt()).isEqualTo(4000);
    assertThat(
            dtos.get("LeaveRejectRequest")
                .get("fields")
                .get("comments")
                .get("required")
                .asBoolean())
        .isTrue();
  }

  @Test
  void p3EmployeeDtosPinLegacyRules() {
    ObjectNode root = new ValidationSchemaExporter().export("x", 8);
    assertThat(root.get("modules").get(3).asText()).isEqualTo("p3-employee");
    assertThat(root.get("parameters").get("HR.MAX_FUTURE_HIRE_DAYS").asInt()).isEqualTo(90);
    JsonNode dtos = root.get("dtos");
    for (String dto :
        new String[] {
          "EmployeeListQuery",
          "EmployeeCreateRequest",
          "EmployeeUpdateRequest",
          "EmployeeTerminateRequest",
          "EmployeeTransferRequest",
          "SalaryChangeRequest",
          "DependentRequest",
          "EmergencyContactRequest"
        }) {
      assertThat(dtos.get(dto).get("module").asText()).isEqualTo("p3-employee");
    }
    JsonNode create = dtos.get("EmployeeCreateRequest").get("fields");
    assertThat(create.has("empNumber")).isFalse();
    assertThat(dtos.get("EmployeeUpdateRequest").get("fields").has("employmentStatus")).isFalse();
    JsonNode hire = create.get("hireDate").get("rules").get(0);
    assertThat(hire.get("kind").asText()).isEqualTo("custom");
    assertThat(hire.get("value").asText()).isEqualTo("90");
    assertThat(hire.get("parameter").asText()).isEqualTo("HR.MAX_FUTURE_HIRE_DAYS");
    assertThat(hire.get("errorCode").asText()).isEqualTo("-20501");
    assertThat(create.get("email").get("format").asText()).isEqualTo("email");
    assertThat(create.get("email").has("pattern")).isFalse();
    JsonNode ssn = create.get("ssn");
    assertThat(ssn.get("sensitive").asBoolean()).isTrue();
    assertThat(ssn.get("pattern").asText()).isEqualTo("^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$");
    assertThat(ssn.get("rules").get(0).get("kind").asText()).isEqualTo("pattern");
    assertThat(create.get("phoneWork").get("pattern").asText())
        .isEqualTo(create.get("phoneMobile").get("pattern").asText());
    JsonNode salary = dtos.get("SalaryChangeRequest").get("fields").get("baseSalary");
    assertThat(salary.get("min").doubleValue()).isEqualTo(0.01);
    assertThat(salary.get("rules")).hasSize(1);
    assertThat(salary.get("rules").get(0).get("errorCode").asText()).isEqualTo("-20101");
  }

  @Test
  void p4PayrollDtosPinLegacyRules() {
    ObjectNode root = new ValidationSchemaExporter().export("x", 8);
    assertThat(root.get("modules").get(4).asText()).isEqualTo("p4-payroll");
    JsonNode dtos = root.get("dtos");
    for (String dto :
        new String[] {
          "PayPeriodListQuery",
          "PayrollRunListQuery",
          "PayrollRunCreateRequest",
          "PayrollRunReverseRequest",
          "PayrollDetailListQuery"
        }) {
      assertThat(dtos.get(dto).get("module").asText()).isEqualTo("p4-payroll");
    }
    JsonNode runType = dtos.get("PayrollRunCreateRequest").get("fields").get("runType");
    assertThat(runType.get("required").asBoolean()).isTrue();
    assertThat(runType.get("values"))
        .extracting(JsonNode::asText)
        .containsExactly("REGULAR", "SUPPLEMENTAL", "BONUS", "FINAL");
    JsonNode reason = dtos.get("PayrollRunReverseRequest").get("fields").get("reason");
    assertThat(reason.get("required").asBoolean()).isTrue();
    assertThat(reason.get("maxLength").asInt()).isEqualTo(4000);
    assertThat(dtos.get("PayPeriodListQuery").get("fields").get("status").get("values"))
        .extracting(JsonNode::asText)
        .containsExactly("OPEN", "PROCESSING", "CLOSED", "REVERSED");
    assertThat(dtos.get("PayrollRunListQuery").get("fields").get("status").get("values"))
        .extracting(JsonNode::asText)
        .containsExactly(
            "PENDING", "CALCULATING", "CALCULATED", "APPROVED", "PAID", "REVERSED", "ERROR");
  }

  @Test
  void hashIsStable() {
    ValidationSchemaExporter e = new ValidationSchemaExporter();
    assertThat(e.export("a", 8).get("sourceHash")).isEqualTo(e.export("b", 8).get("sourceHash"));
    assertThat(e.export("a", 8).get("sourceHash"))
        .isNotEqualTo(e.export("a", 10).get("sourceHash"));
  }
}
