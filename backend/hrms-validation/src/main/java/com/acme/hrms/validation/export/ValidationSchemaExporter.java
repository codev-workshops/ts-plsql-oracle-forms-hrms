package com.acme.hrms.validation.export;

import com.acme.hrms.validation.dto.ChangePasswordRequest;
import com.acme.hrms.validation.dto.EmployeeSearchQuery;
import com.acme.hrms.validation.dto.LoginRequest;
import com.acme.hrms.validation.dto.ProxyModule;
import com.acme.hrms.validation.dto.SsoExchangeRequest;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import com.acme.hrms.validation.password.PasswordPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits {@code frontend/src/generated/validation-schema.json} (contracts/p0-foundation/README.md
 * "exporter output format") from the Bean Validation annotations of the P0 DTOs and {@link
 * PasswordPolicy}. Usage: {@code java ... ValidationSchemaExporter <output-file> [version]}.
 */
public final class ValidationSchemaExporter {

  public static final String SCHEMA = "https://hrms.example/schemas/validation-schema/v1";
  public static final String GENERATOR = "hrms-validation:exporter";
  public static final String MODULE = "p0-foundation";
  public static final int SESSION_TIMEOUT_MIN_DEFAULT = 30;

  private static final Map<String, Class<?>> DTOS = new LinkedHashMap<>();

  static {
    DTOS.put("LoginRequest", LoginRequest.class);
    DTOS.put("ChangePasswordRequest", ChangePasswordRequest.class);
    DTOS.put("SsoExchangeRequest", SsoExchangeRequest.class);
    DTOS.put("EmployeeSearchQuery", EmployeeSearchQuery.class);
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
    for (Map.Entry<String, Class<?>> e : DTOS.entrySet()) {
      dtos.set(e.getKey(), describeDto(e.getValue(), passwordMinLength));
    }
    ObjectNode parameters = mapper.createObjectNode();
    parameters.put("SECURITY.PASSWORD_MIN_LENGTH", passwordMinLength);
    parameters.put("SECURITY.SESSION_TIMEOUT_MIN", SESSION_TIMEOUT_MIN_DEFAULT);

    ObjectNode root = mapper.createObjectNode();
    root.put("$schema", SCHEMA);
    root.put("schemaVersion", 1);
    root.put("generator", GENERATOR);
    root.put("generatorVersion", generatorVersion);
    root.put("sourceHash", sha256(parameters.toString() + dtos.toString()));
    root.put("module", MODULE);
    root.set("parameters", parameters);
    root.set("dtos", dtos);
    return root;
  }

  private ObjectNode describeDto(Class<?> dto, int passwordMinLength) {
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
      if (pattern != null) {
        n.put("pattern", pattern.regexp());
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
