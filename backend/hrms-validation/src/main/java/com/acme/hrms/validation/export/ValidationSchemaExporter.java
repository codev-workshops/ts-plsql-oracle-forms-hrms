package com.acme.hrms.validation.export;

import com.acme.hrms.validation.constraints.HireDateWithinLimit;
import com.acme.hrms.validation.constraints.Ssn;
import com.acme.hrms.validation.dto.ChangePasswordRequest;
import com.acme.hrms.validation.dto.EmployeeSearchQuery;
import com.acme.hrms.validation.dto.LoginRequest;
import com.acme.hrms.validation.dto.ProxyModule;
import com.acme.hrms.validation.dto.SsoExchangeRequest;
import com.acme.hrms.validation.dto.admin.AccrualRunRequest;
import com.acme.hrms.validation.dto.admin.AuditLogSearchQuery;
import com.acme.hrms.validation.dto.admin.CarryoverRunRequest;
import com.acme.hrms.validation.dto.admin.DepartmentRequest;
import com.acme.hrms.validation.dto.admin.JobGradeRequest;
import com.acme.hrms.validation.dto.admin.JobTitleRequest;
import com.acme.hrms.validation.dto.admin.LeaveTypeRequest;
import com.acme.hrms.validation.dto.admin.LocationRequest;
import com.acme.hrms.validation.dto.admin.SystemParameterRequest;
import com.acme.hrms.validation.dto.admin.SystemParameterUpdateRequest;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmergencyContactRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeListQuery;
import com.acme.hrms.validation.dto.employee.EmployeeTerminateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTransferRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import com.acme.hrms.validation.dto.integration.BenefitsFeedRequest;
import com.acme.hrms.validation.dto.integration.GlFeedRequest;
import com.acme.hrms.validation.dto.integration.IntegrationFileListQuery;
import com.acme.hrms.validation.dto.integration.TimeAttendanceLine;
import com.acme.hrms.validation.dto.leave.BusinessDaysQuery;
import com.acme.hrms.validation.dto.leave.LeaveApproveRequest;
import com.acme.hrms.validation.dto.leave.LeaveCancelRequest;
import com.acme.hrms.validation.dto.leave.LeaveRejectRequest;
import com.acme.hrms.validation.dto.leave.LeaveRequestCreateRequest;
import com.acme.hrms.validation.dto.payroll.PayPeriodListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollDetailListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollRunCreateRequest;
import com.acme.hrms.validation.dto.payroll.PayrollRunListQuery;
import com.acme.hrms.validation.dto.payroll.PayrollRunReverseRequest;
import com.acme.hrms.validation.dto.performance.AcknowledgeRequest;
import com.acme.hrms.validation.dto.performance.GoalProgressRequest;
import com.acme.hrms.validation.dto.performance.GoalRequest;
import com.acme.hrms.validation.dto.performance.ManagerReviewRequest;
import com.acme.hrms.validation.dto.performance.ReviewCycleRequest;
import com.acme.hrms.validation.dto.performance.SelfAssessmentRequest;
import com.acme.hrms.validation.dto.reporting.EmployeeCompensationQuery;
import com.acme.hrms.validation.dto.reporting.EmployeeDirectoryQuery;
import com.acme.hrms.validation.dto.reporting.LeaveSummaryQuery;
import com.acme.hrms.validation.dto.reporting.OrgHierarchyQuery;
import com.acme.hrms.validation.dto.reporting.PayrollLatestQuery;
import com.acme.hrms.validation.dto.reporting.PendingApprovalsQuery;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import com.acme.hrms.validation.password.PasswordPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits {@code frontend/src/generated/validation-schema.json} (contracts/p0-foundation/README.md
 * "exporter output format", extended by contracts/p1-performance/README.md) from the Bean
 * Validation annotations of the request DTOs of every frozen phase (contracts/p2-leave/README.md
 * adds boolean fields and {@code custom} date rules; contracts/p3-employee/README.md adds {@code
 * sensitive}, string {@code pattern} rules and the {@code HR.MAX_FUTURE_HIRE_DAYS} parameter) and
 * {@link PasswordPolicy}. Usage: {@code java ... ValidationSchemaExporter <output-file> [version]}.
 */
public final class ValidationSchemaExporter {

  public static final String SCHEMA = "https://hrms.example/schemas/validation-schema/v1";
  public static final String GENERATOR = "hrms-validation:exporter";
  public static final String MODULE = "hrms";
  public static final String MODULE_P0 = "p0-foundation";
  public static final String MODULE_P1 = "p1-performance";
  public static final String MODULE_P2 = "p2-leave";
  public static final String MODULE_P3 = "p3-employee";
  public static final String MODULE_P4 = "p4-payroll";
  public static final String MODULE_P5 = "p5-reporting-decommission";
  public static final List<String> MODULES =
      List.of(MODULE_P0, MODULE_P1, MODULE_P2, MODULE_P3, MODULE_P4, MODULE_P5);
  public static final int SESSION_TIMEOUT_MIN_DEFAULT = 30;

  /** DTO name -> (owning contract module, class); insertion order is the output order. */
  private static final Map<String, Map.Entry<String, Class<?>>> DTOS = new LinkedHashMap<>();

  static {
    register(MODULE_P0, "LoginRequest", LoginRequest.class);
    register(MODULE_P0, "ChangePasswordRequest", ChangePasswordRequest.class);
    register(MODULE_P0, "SsoExchangeRequest", SsoExchangeRequest.class);
    register(MODULE_P0, "EmployeeSearchQuery", EmployeeSearchQuery.class);
    register(MODULE_P1, "ReviewCycleRequest", ReviewCycleRequest.class);
    register(MODULE_P1, "SelfAssessmentRequest", SelfAssessmentRequest.class);
    register(MODULE_P1, "ManagerReviewRequest", ManagerReviewRequest.class);
    register(MODULE_P1, "AcknowledgeRequest", AcknowledgeRequest.class);
    register(MODULE_P1, "GoalRequest", GoalRequest.class);
    register(MODULE_P1, "GoalProgressRequest", GoalProgressRequest.class);
    register(MODULE_P2, "LeaveRequestCreateRequest", LeaveRequestCreateRequest.class);
    register(MODULE_P2, "LeaveCancelRequest", LeaveCancelRequest.class);
    register(MODULE_P2, "LeaveApproveRequest", LeaveApproveRequest.class);
    register(MODULE_P2, "LeaveRejectRequest", LeaveRejectRequest.class);
    register(MODULE_P2, "BusinessDaysQuery", BusinessDaysQuery.class);
    register(MODULE_P3, "EmployeeListQuery", EmployeeListQuery.class);
    register(MODULE_P3, "EmployeeCreateRequest", EmployeeCreateRequest.class);
    register(MODULE_P3, "EmployeeUpdateRequest", EmployeeUpdateRequest.class);
    register(MODULE_P3, "EmployeeTerminateRequest", EmployeeTerminateRequest.class);
    register(MODULE_P3, "EmployeeTransferRequest", EmployeeTransferRequest.class);
    register(MODULE_P3, "SalaryChangeRequest", SalaryChangeRequest.class);
    register(MODULE_P3, "DependentRequest", DependentRequest.class);
    register(MODULE_P3, "EmergencyContactRequest", EmergencyContactRequest.class);
    register(MODULE_P4, "PayPeriodListQuery", PayPeriodListQuery.class);
    register(MODULE_P4, "PayrollRunListQuery", PayrollRunListQuery.class);
    register(MODULE_P4, "PayrollRunCreateRequest", PayrollRunCreateRequest.class);
    register(MODULE_P4, "PayrollRunReverseRequest", PayrollRunReverseRequest.class);
    register(MODULE_P4, "PayrollDetailListQuery", PayrollDetailListQuery.class);
    register(MODULE_P5, "EmployeeDirectoryQuery", EmployeeDirectoryQuery.class);
    register(MODULE_P5, "OrgHierarchyQuery", OrgHierarchyQuery.class);
    register(MODULE_P5, "EmployeeCompensationQuery", EmployeeCompensationQuery.class);
    register(MODULE_P5, "LeaveSummaryQuery", LeaveSummaryQuery.class);
    register(MODULE_P5, "PayrollLatestQuery", PayrollLatestQuery.class);
    register(MODULE_P5, "PendingApprovalsQuery", PendingApprovalsQuery.class);
    register(MODULE_P5, "DepartmentRequest", DepartmentRequest.class);
    register(MODULE_P5, "JobGradeRequest", JobGradeRequest.class);
    register(MODULE_P5, "JobTitleRequest", JobTitleRequest.class);
    register(MODULE_P5, "LocationRequest", LocationRequest.class);
    register(MODULE_P5, "LeaveTypeRequest", LeaveTypeRequest.class);
    register(MODULE_P5, "SystemParameterRequest", SystemParameterRequest.class);
    register(MODULE_P5, "SystemParameterUpdateRequest", SystemParameterUpdateRequest.class);
    register(MODULE_P5, "AccrualRunRequest", AccrualRunRequest.class);
    register(MODULE_P5, "CarryoverRunRequest", CarryoverRunRequest.class);
    register(MODULE_P5, "AuditLogSearchQuery", AuditLogSearchQuery.class);
    register(MODULE_P5, "GlFeedRequest", GlFeedRequest.class);
    register(MODULE_P5, "BenefitsFeedRequest", BenefitsFeedRequest.class);
    register(MODULE_P5, "TimeAttendanceLine", TimeAttendanceLine.class);
    register(MODULE_P5, "IntegrationFileListQuery", IntegrationFileListQuery.class);
  }

  private static void register(String module, String name, Class<?> dto) {
    DTOS.put(name, Map.entry(module, dto));
  }

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args.length > 0 ? args[0] : "frontend/src/generated/validation-schema.json");
    String version = args.length > 1 ? args[1] : "0.1.0-SNAPSHOT";
    ValidationSchemaExporter exporter = new ValidationSchemaExporter();
    String json = exporter.exportJson(version, PasswordPolicy.DEFAULT_MIN_LENGTH);
    Files.createDirectories(out.toAbsolutePath().getParent());
    Files.writeString(out, json + "\n", StandardCharsets.UTF_8);
    System.out.println("wrote " + out.toAbsolutePath());
  }

  public String exportJson(String generatorVersion, int passwordMinLength) {
    try {
      return mapper.writeValueAsString(export(generatorVersion, passwordMinLength));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public ObjectNode export(String generatorVersion, int passwordMinLength) {
    ObjectNode dtos = mapper.createObjectNode();
    for (Map.Entry<String, Map.Entry<String, Class<?>>> e : DTOS.entrySet()) {
      dtos.set(
          e.getKey(),
          describeDto(e.getValue().getKey(), e.getValue().getValue(), passwordMinLength));
    }
    ObjectNode parameters = mapper.createObjectNode();
    parameters.put("SECURITY.PASSWORD_MIN_LENGTH", passwordMinLength);
    parameters.put("SECURITY.SESSION_TIMEOUT_MIN", SESSION_TIMEOUT_MIN_DEFAULT);
    parameters.put(HireDateWithinLimit.PARAMETER, HireDateWithinLimit.DEFAULT_MAX_FUTURE_DAYS);

    ObjectNode root = mapper.createObjectNode();
    root.put("$schema", SCHEMA);
    root.put("schemaVersion", 1);
    root.put("generator", GENERATOR);
    root.put("generatorVersion", generatorVersion);
    root.put("sourceHash", sha256(parameters.toString() + dtos.toString()));
    root.put("module", MODULE);
    ArrayNode modules = root.putArray("modules");
    MODULES.forEach(modules::add);
    root.set("parameters", parameters);
    root.set("dtos", dtos);
    return root;
  }

  private ObjectNode describeDto(String module, Class<?> dto, int passwordMinLength) {
    ObjectNode fields = mapper.createObjectNode();
    for (Field f : dto.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      if (f.getAnnotations().length == 0) {
        continue; // e.g. excludeSelf: a plain flag, nothing for the client to pre-check
      }
      fields.set(f.getName(), describeField(dto, f, passwordMinLength));
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("module", module);
    node.set("fields", fields);
    return node;
  }

  private ObjectNode describeField(Class<?> dto, Field f, int passwordMinLength) {
    ObjectNode n = mapper.createObjectNode();
    FieldMeta meta = f.getAnnotation(FieldMeta.class);
    boolean required =
        f.isAnnotationPresent(NotNull.class) || f.isAnnotationPresent(NotBlank.class);
    ObjectNode messages = mapper.createObjectNode();

    AllowedValues allowed = f.getAnnotation(AllowedValues.class);
    if (f.getType().isEnum() || allowed != null) {
      n.put("type", "enum");
      n.put("required", required);
      ArrayNode values = n.putArray("values");
      if (allowed != null) {
        for (String v : allowed.value()) {
          values.add(v);
        }
      } else if (f.getType() == ProxyModule.class) {
        for (ProxyModule m : ProxyModule.values()) {
          values.add(m.wire());
        }
      } else {
        for (Object c : f.getType().getEnumConstants()) {
          values.add(c.toString());
        }
      }
    } else if (f.getType() == Integer.class || f.getType() == int.class) {
      n.put("type", "integer");
      n.put("required", required);
      Min min = f.getAnnotation(Min.class);
      Max max = f.getAnnotation(Max.class);
      if (min != null) {
        n.put("min", (int) min.value());
      }
      if (max != null) {
        n.put("max", (int) max.value());
      }
    } else if (f.getType() == BigDecimal.class) {
      n.put("type", "decimal");
      n.put("required", required);
      DecimalMin min = f.getAnnotation(DecimalMin.class);
      DecimalMax max = f.getAnnotation(DecimalMax.class);
      Digits digits = f.getAnnotation(Digits.class);
      if (min != null) {
        n.put("min", Double.parseDouble(min.value()));
      }
      if (max != null) {
        n.put("max", Double.parseDouble(max.value()));
      }
      if (digits != null) {
        n.put("scale", digits.fraction());
      }
      if (meta != null && !meta.ruleId().isEmpty() && min != null) {
        ArrayNode rules = n.putArray("rules");
        ObjectNode rmin = rules.addObject();
        rmin.put("id", meta.ruleId() + ".min");
        rmin.put("kind", "min");
        rmin.put("value", Double.parseDouble(min.value()));
        rmin.put("errorCode", meta.ruleErrorCode());
        rmin.put("message", meta.ruleMessage());
        if (max != null) {
          ObjectNode rmax = rules.addObject();
          rmax.put("id", meta.ruleId() + ".max");
          rmax.put("kind", "max");
          rmax.put("value", Double.parseDouble(max.value()));
          rmax.put("errorCode", meta.ruleErrorCode());
          rmax.put("message", meta.ruleMessage());
        }
      }
    } else if (f.getType() == LocalDate.class) {
      n.put("type", "date");
      n.put("required", required);
      n.put("format", "date");
      HireDateWithinLimit hireLimit = f.getAnnotation(HireDateWithinLimit.class);
      if (meta != null && !meta.ruleId().isEmpty()) {
        ObjectNode rule = n.putArray("rules").addObject();
        rule.put("id", meta.ruleId());
        rule.put("kind", "custom");
        if (hireLimit != null) {
          rule.put("value", String.valueOf(hireLimit.maxFutureDays()));
          rule.put("parameter", HireDateWithinLimit.PARAMETER);
        } else {
          rule.put("value", meta.ruleValue());
        }
        rule.put("errorCode", meta.ruleErrorCode());
        rule.put("message", meta.ruleMessage());
      }
    } else if (f.getType() == Boolean.class || f.getType() == boolean.class) {
      n.put("type", "boolean");
      n.put("required", required);
    } else {
      n.put("type", "string");
      n.put("required", required);
      n.put("trim", meta != null && meta.trim());
      Size size = f.getAnnotation(Size.class);
      boolean isNewPassword =
          dto == ChangePasswordRequest.class && f.getName().equals("newPassword");
      int minLength = size != null ? size.min() : 0;
      if (isNewPassword) {
        minLength = passwordMinLength;
      }
      if (minLength > 0) {
        n.put("minLength", minLength);
      }
      if (size != null && size.max() != Integer.MAX_VALUE) {
        n.put("maxLength", size.max());
      }
      if (f.isAnnotationPresent(Email.class)) {
        n.put("format", "email");
      }
      Pattern pattern = f.getAnnotation(Pattern.class);
      String regex =
          pattern != null
              ? pattern.regexp()
              : f.isAnnotationPresent(Ssn.class) ? Ssn.PATTERN : null;
      if (regex != null) {
        n.put("pattern", regex);
      }
      if (meta != null && !meta.ruleId().isEmpty() && regex != null && !isNewPassword) {
        ObjectNode rule = n.putArray("rules").addObject();
        rule.put("id", meta.ruleId());
        rule.put("kind", "pattern");
        rule.put("value", regex);
        rule.put("errorCode", meta.ruleErrorCode());
        rule.put("message", meta.ruleMessage());
      }
      if (isNewPassword) {
        ArrayNode rules = n.putArray("rules");
        for (PasswordPolicy.Rule r : PasswordPolicy.rules(passwordMinLength)) {
          ObjectNode rn = rules.addObject();
          rn.put("id", r.id());
          rn.put("kind", r.kind());
          if (r.value() instanceof Integer i) {
            rn.put("value", i);
          } else {
            rn.put("value", r.value().toString());
          }
          if (r.parameter() != null) {
            rn.put("parameter", r.parameter());
          }
          rn.put("errorCode", r.code().value());
          rn.put("message", r.message());
        }
      }
    }
    if (meta != null && meta.sensitive()) {
      n.put("sensitive", true);
    }
    if (meta != null) {
      if (!meta.requiredMessage().isEmpty()) {
        messages.put("required", meta.requiredMessage());
      }
      if (!meta.formatMessage().isEmpty()) {
        messages.put("format", meta.formatMessage());
      }
      if (!meta.patternMessage().isEmpty()) {
        messages.put("pattern", meta.patternMessage());
      }
      if (!meta.maxLengthMessage().isEmpty()) {
        messages.put("maxLength", meta.maxLengthMessage());
      }
    }
    n.set("messages", messages);
    return n;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
