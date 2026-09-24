package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.SystemParameter;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.SystemParameterRequest;
import com.acme.hrms.validation.dto.admin.SystemParameterUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner of SYSTEM_PARAMETERS. Other modules read values through {@link #value(String, String)} at
 * request time (no restart, no cache).
 */
@Service
public class SystemParameterAdminService {
  private static final String SELECT = "select p.* from system_parameters p";

  private static final RowMapper<SystemParameter> MAPPER =
      (rs, i) ->
          new SystemParameter(
              rs.getInt("param_id"),
              rs.getString("param_group"),
              rs.getString("param_code"),
              rs.getString("param_value"),
              rs.getString("param_description"),
              rs.getString("data_type"),
              AdminSupport.flag(rs.getString("editable_flag")),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final AdminSupport s;

  public SystemParameterAdminService(AdminSupport s) {
    this.s = s;
  }

  public Optional<String> value(String group, String code) {
    List<String> v =
        s.jdbc()
            .queryForList(
                "select param_value from system_parameters"
                    + " where param_group = :g and param_code = :c",
                Map.of("g", group, "c", code),
                String.class);
    return v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
  }

  public List<SystemParameter> list(@Nullable String group) {
    Map<String, Object> p = new HashMap<>();
    String where = "";
    if (group != null && !group.isBlank()) {
      where = " where p.param_group = :g";
      p.put("g", group);
    }
    return s.jdbc().query(SELECT + where + " order by p.param_group, p.param_code", p, MAPPER);
  }

  public SystemParameter get(int paramId) {
    List<SystemParameter> rows =
        s.jdbc().query(SELECT + " where p.param_id = :id", Map.of("id", paramId), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public SystemParameter create(SystemParameterRequest r) {
    if (s.count(
            "select count(*) from system_parameters where param_group = :g and param_code = :c",
            Map.of("g", r.getParamGroup(), "c", r.getParamCode()))
        > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT,
          "paramCode",
          r.getParamGroup() + "." + r.getParamCode());
    }
    checkValue(r.getDataType(), r.getParamValue());
    int id =
        Objects.requireNonNull(
            s.jdbc().queryForObject("select nextval('seq_system_param')", Map.of(), Integer.class));
    Map<String, Object> p = new HashMap<>();
    p.put("id", id);
    p.put("g", r.getParamGroup());
    p.put("c", r.getParamCode());
    p.put("v", r.getParamValue());
    p.put("d", r.getParamDescription());
    p.put("t", r.getDataType());
    p.put("e", AdminSupport.flag(r.getEditableFlag(), true));
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into system_parameters (param_id, param_group, param_code, param_value,
                                           param_description, data_type, editable_flag,
                                           created_by, created_date)
            values (:id, :g, :c, :v, :d, :t, :e, :actor, :now)
            """,
            p);
    SystemParameter created = get(id);
    s.audit("SYSTEM_PARAMETERS", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public SystemParameter update(int paramId, SystemParameterUpdateRequest r) {
    SystemParameter old = get(paramId);
    if (!old.editableFlag()) {
      throw AdminSupport.error(
          ErrorCode.PARAMETER_NOT_EDITABLE, null, old.paramGroup() + "." + old.paramCode());
    }
    checkValue(old.dataType(), r.getParamValue());
    Map<String, Object> p = new HashMap<>();
    p.put("id", paramId);
    p.put("v", r.getParamValue());
    p.put("d", r.getParamDescription());
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            "update system_parameters set param_value = :v, param_description = :d,"
                + " modified_by = :actor, modified_date = :now where param_id = :id",
            p);
    SystemParameter updated = get(paramId);
    s.audit("SYSTEM_PARAMETERS", paramId, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void delete(int paramId) {
    SystemParameter old = get(paramId);
    if (!old.editableFlag()) {
      throw AdminSupport.error(
          ErrorCode.PARAMETER_NOT_EDITABLE, null, old.paramGroup() + "." + old.paramCode());
    }
    s.jdbc().update("delete from system_parameters where param_id = :id", Map.of("id", paramId));
    s.audit("SYSTEM_PARAMETERS", paramId, Action.DELETE, old, null);
  }

  static void checkValue(String dataType, String value) {
    boolean ok =
        switch (dataType) {
          case "NUMBER" -> isDecimal(value);
          case "DATE" -> isIsoDate(value);
          case "BOOLEAN" -> value.equals("Y") || value.equals("N");
          default -> true;
        };
    if (!ok) {
      throw new HrmsException(
          ErrorCode.REFERENCE_VALUE_RULE,
          "Parameter value does not match data type " + dataType,
          "paramValue");
    }
  }

  private static boolean isDecimal(String v) {
    try {
      new BigDecimal(v.trim());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isIsoDate(String v) {
    try {
      LocalDate.parse(v.trim());
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
