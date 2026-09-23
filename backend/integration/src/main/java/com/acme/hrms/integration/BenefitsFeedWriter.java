package com.acme.hrms.integration;

import com.acme.hrms.employee.SensitiveFieldCipher;
import com.acme.hrms.integration.IntegrationDtos.Artefact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code PKG_INTEGRATION.export_benefits_feed} in the frozen fixed-width ADP layout: one {@code E}
 * record per active employee hired on/before the effective date, one {@code D} record per enrolled
 * active dependent. SSNs are masked {@code ***-**-dddd} (SEC-05); the legacy full SSN is
 * intentionally not reproduced.
 */
@Component
public class BenefitsFeedWriter {
  private static final DateTimeFormatter D8 = DateTimeFormatter.BASIC_ISO_DATE;

  private final NamedParameterJdbcTemplate jdbc;
  private final SensitiveFieldCipher cipher;
  private final Clock clock;

  public BenefitsFeedWriter(
      NamedParameterJdbcTemplate jdbc, SensitiveFieldCipher cipher, Clock clock) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.clock = clock;
  }

  public Artefact write(@Nullable LocalDate effectiveDate) {
    LocalDate asOf = effectiveDate == null ? LocalDate.now(clock) : effectiveDate;
    StringBuilder sb = new StringBuilder();
    int[] count = {0};
    jdbc.query(
        """
        select e.emp_id, e.emp_number, e.last_name, e.first_name, e.ssn_encrypted,
               e.date_of_birth, e.gender, e.hire_date, e.marital_status,
               s.base_salary, d.dept_code, e.location_code
          from employees e
          join departments d on d.dept_id = e.dept_id
          left join lateral (
               select sr.base_salary from salary_records sr
                where sr.emp_id = e.emp_id and sr.active_flag = 'Y'
                order by sr.effective_date desc, sr.salary_id desc limit 1) s on true
         where e.employment_status = 'ACTIVE' and e.hire_date <= :asOf
         order by e.emp_number
        """,
        Map.of("asOf", asOf),
        rs -> {
          long empId = rs.getLong("emp_id");
          sb.append('E')
              .append(pad(rs.getString("emp_number"), 10))
              .append(pad(rs.getString("last_name"), 30))
              .append(pad(rs.getString("first_name"), 30))
              .append(pad(mask(rs.getString("ssn_encrypted")), 11))
              .append(pad(date(rs.getObject("date_of_birth", LocalDate.class)), 8))
              .append(pad(rs.getString("gender"), 1))
              .append(pad(date(rs.getObject("hire_date", LocalDate.class)), 8))
              .append(pad(rs.getString("marital_status"), 1))
              .append(money(rs.getBigDecimal("base_salary")))
              .append(pad(rs.getString("dept_code"), 20))
              .append(pad(rs.getString("location_code"), 10))
              .append(GlFeedWriter.LF);
          count[0]++;
          count[0] += dependents(empId, sb);
        });
    String name = "BENEFITS_" + asOf.format(D8) + ".txt";
    return new Artefact(
        "BENEFITS_FEED",
        name,
        "SUCCESS",
        IntegrationFileRepository.utf8(sb.toString()),
        count[0],
        asOf.toString(),
        "text/plain; charset=utf-8",
        null);
  }

  private int dependents(long empId, StringBuilder sb) {
    int[] n = {0};
    jdbc.query(
        """
        select relationship, last_name, first_name, date_of_birth, ssn_encrypted
          from employee_dependents
         where emp_id = :id and active_flag = 'Y' and benefits_enrolled = 'Y'
         order by dependent_id
        """,
        Map.of("id", empId),
        rs -> {
          sb.append('D')
              .append(pad(rs.getString("relationship"), 20))
              .append(pad(rs.getString("last_name"), 30))
              .append(pad(rs.getString("first_name"), 30))
              .append(pad(date(rs.getObject("date_of_birth", LocalDate.class)), 8))
              .append(pad(mask(rs.getString("ssn_encrypted")), 11))
              .append(GlFeedWriter.LF);
          n[0]++;
        });
    return n[0];
  }

  @Nullable
  private String mask(@Nullable String encrypted) {
    String ssn = cipher.decrypt(encrypted);
    if (ssn == null) {
      return null;
    }
    String digits = ssn.replaceAll("\\D", "");
    if (digits.length() < 4) {
      return null;
    }
    return "***-**-" + digits.substring(digits.length() - 4);
  }

  static String pad(@Nullable String v, int width) {
    String s = v == null ? "" : v;
    if (s.length() > width) {
      return s.substring(0, width);
    }
    return s + " ".repeat(width - s.length());
  }

  static String date(@Nullable LocalDate d) {
    return d == null ? null : d.format(D8);
  }

  /** {@code 12.2}: 12 characters, right-aligned, two decimals, zero-filled. */
  static String money(@Nullable BigDecimal v) {
    String s = (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    return s.length() >= 12 ? s : "0".repeat(12 - s.length()) + s;
  }
}
