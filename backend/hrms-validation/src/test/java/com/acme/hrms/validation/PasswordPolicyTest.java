package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.error.PasswordPolicyException;
import com.acme.hrms.validation.password.PasswordPolicy;
import org.junit.jupiter.api.Test;

/** PKG_SECURITY.change_password rule order: -20310, then -20311, then -20312. */
class PasswordPolicyTest {

  @Test
  void tooShortWinsOverEverything() {
    assertThatThrownBy(() -> PasswordPolicy.check("ab", 8))
        .isInstanceOf(PasswordPolicyException.class)
        .extracting(e -> ((PasswordPolicyException) e).code().value())
        .isEqualTo("-20310");
  }

  @Test
  void uppercaseBeforeDigit() {
    assertThatThrownBy(() -> PasswordPolicy.check("abcdefgh", 8))
        .extracting(e -> ((PasswordPolicyException) e).code().value())
        .isEqualTo("-20311");
    assertThatThrownBy(() -> PasswordPolicy.check("Abcdefgh", 8))
        .extracting(e -> ((PasswordPolicyException) e).code().value())
        .isEqualTo("-20312");
  }

  @Test
  void validPassesAndMinLengthIsParameterised() {
    assertThatCode(() -> PasswordPolicy.check("Abcdefg1", 8)).doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordPolicy.check("Abcdefg1", 12))
        .extracting(e -> ((PasswordPolicyException) e).code().value())
        .isEqualTo("-20310");
    assertThatThrownBy(() -> PasswordPolicy.check("Abcdefg1", 12))
        .hasMessage("Password must be at least 12 characters")
        .extracting(e -> ((PasswordPolicyException) e).field())
        .isEqualTo("newPassword");
  }
}
