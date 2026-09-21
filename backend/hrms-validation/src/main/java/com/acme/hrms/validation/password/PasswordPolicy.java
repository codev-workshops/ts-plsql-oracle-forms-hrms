package com.acme.hrms.validation.password;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.PasswordPolicyException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PKG_SECURITY.change_password complexity rules, evaluated in the legacy order so the first failing
 * rule's code is returned: length (-20310), uppercase (-20311), digit (-20312). The minimum length
 * is {@code SYSTEM_PARAMETERS SECURITY.PASSWORD_MIN_LENGTH} (VAL-06), default 8.
 */
public final class PasswordPolicy {

  public static final String PARAMETER = "SECURITY.PASSWORD_MIN_LENGTH";
  public static final int DEFAULT_MIN_LENGTH = 8;
  public static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
  public static final Pattern DIGIT = Pattern.compile("[0-9]");

  /** Declarative rule list - also the source of the exported {@code rules[]}. */
  public record Rule(
      String id, String kind, Object value, String parameter, ErrorCode code, String message) {}

  private PasswordPolicy() {}

  public static List<Rule> rules(int minLength) {
    return List.of(
        new Rule(
            "password.minLength",
            "minLength",
            minLength,
            PARAMETER,
            ErrorCode.PASSWORD_TOO_SHORT,
            "Password must be at least " + minLength + " characters"),
        new Rule(
            "password.uppercase",
            "pattern",
            UPPERCASE.pattern(),
            null,
            ErrorCode.PASSWORD_NO_UPPERCASE,
            "Password must contain an uppercase letter"),
        new Rule(
            "password.digit",
            "pattern",
            DIGIT.pattern(),
            null,
            ErrorCode.PASSWORD_NO_DIGIT,
            "Password must contain a number"));
  }

  /** Throws the first violated rule as a {@link PasswordPolicyException}. */
  public static void check(String newPassword, int minLength) {
    String value = newPassword == null ? "" : newPassword;
    for (Rule rule : rules(minLength)) {
      boolean ok =
          switch (rule.kind()) {
            case "minLength" -> value.length() >= (Integer) rule.value();
            case "pattern" -> Pattern.compile((String) rule.value()).matcher(value).find();
            default -> throw new IllegalStateException(rule.kind());
          };
      if (!ok) {
        throw new PasswordPolicyException(rule.code(), rule.message());
      }
    }
  }
}
