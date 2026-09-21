package com.acme.hrms.common.param;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PKG_COMMON.get_parameter replacement over {@code system_parameters} (VAL-06 source of truth). */
@Service
public class SystemParameterService {

  public static final String SECURITY_GROUP = "SECURITY";
  public static final String PASSWORD_MIN_LENGTH = "PASSWORD_MIN_LENGTH";
  public static final String SESSION_TIMEOUT_MIN = "SESSION_TIMEOUT_MIN";
  public static final int DEFAULT_PASSWORD_MIN_LENGTH = 8;
  public static final int DEFAULT_SESSION_TIMEOUT_MIN = 30;

  private final JdbcTemplate jdbc;

  public SystemParameterService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> get(String group, String code) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              "select param_value from system_parameters where param_group = ? and param_code"
                  + " = ?",
              String.class,
              group,
              code));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public int getInt(String group, String code, int defaultValue) {
    return get(group, code).map(String::trim).map(Integer::parseInt).orElse(defaultValue);
  }

  public int passwordMinLength() {
    return getInt(SECURITY_GROUP, PASSWORD_MIN_LENGTH, DEFAULT_PASSWORD_MIN_LENGTH);
  }

  public int sessionTimeoutMinutes() {
    return getInt(SECURITY_GROUP, SESSION_TIMEOUT_MIN, DEFAULT_SESSION_TIMEOUT_MIN);
  }
}
