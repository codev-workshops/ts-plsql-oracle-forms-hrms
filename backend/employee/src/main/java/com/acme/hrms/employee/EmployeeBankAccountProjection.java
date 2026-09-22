package com.acme.hrms.employee;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Masked view of {@code employee_bank_accounts} for other modules (P4 register): bank name plus the
 * last four digits of routing / account number, never the full values. The employee module stays
 * the only owner of the table.
 */
@Component
public class EmployeeBankAccountProjection {

  private final JdbcTemplate jdbc;
  private final SensitiveFieldCipher cipher;

  public EmployeeBankAccountProjection(JdbcTemplate jdbc, SensitiveFieldCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  /** Primary active account of the employee, masked; empty when none exists. */
  public Optional<MaskedBankAccount> primary(long empId) {
    return jdbc
        .query(
            "select bank_name, routing_number, account_number_enc from employee_bank_accounts"
                + " where emp_id = ? and active_flag = 'Y'"
                + " order by case when deposit_type = 'FULL' then 0 else 1 end, priority_order,"
                + " bank_acct_id limit 1",
            (rs, i) ->
                new MaskedBankAccount(
                    rs.getString("bank_name"),
                    mask(rs.getString("routing_number")),
                    mask(decryptQuietly(rs.getString("account_number_enc")))),
            empId)
        .stream()
        .findFirst();
  }

  @Nullable
  private String decryptQuietly(@Nullable String stored) {
    try {
      return cipher.decrypt(stored);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String mask(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return "****";
    }
    String digits = value.trim();
    String tail = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    return "****" + tail;
  }

  public record MaskedBankAccount(
      @Nullable String bankName, String routingLast4, String accountLast4) {}
}
