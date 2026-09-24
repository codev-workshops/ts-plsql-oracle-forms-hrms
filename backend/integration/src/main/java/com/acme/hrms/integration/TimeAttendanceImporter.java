package com.acme.hrms.integration;

import com.acme.hrms.common.error.ApiError;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.trace.TraceContext;
import com.acme.hrms.integration.IntegrationDtos.Artefact;
import com.acme.hrms.integration.IntegrationDtos.TimeAttendanceLineResult;
import com.acme.hrms.validation.dto.integration.IntegrationRules;
import com.acme.hrms.validation.dto.integration.TimeAttendanceLine;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code PKG_INTEGRATION.import_time_attendance} – BUG-08: validate and stage only. The accepted
 * lines become the staged file content ({@code emp_number,date,hours_regular,hours_overtime});
 * nothing else is written.
 */
@Component
public class TimeAttendanceImporter {
  public record Outcome(Artefact artefact, List<TimeAttendanceLineResult> lines, int accepted) {}

  private record Emp(long empId, boolean active, @Nullable Boolean overtimeEligible) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final Validator validator;

  public TimeAttendanceImporter(NamedParameterJdbcTemplate jdbc, Validator validator) {
    this.jdbc = jdbc;
    this.validator = validator;
  }

  public Outcome parse(byte[] content, boolean hasHeader, String originalName) {
    String text = new String(content, StandardCharsets.UTF_8);
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }
    String[] raw = text.split("\r\n|\n|\r", -1);
    int lineCount = raw.length > 0 && raw[raw.length - 1].isEmpty() ? raw.length - 1 : raw.length;
    if (lineCount > IntegrationRules.UPLOAD_MAX_LINES) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED,
          "File exceeds " + IntegrationRules.UPLOAD_MAX_LINES + " lines",
          "file");
    }
    Map<String, Emp> employees = employees();
    Set<String> seen = new HashSet<>();
    List<TimeAttendanceLineResult> results = new ArrayList<>();
    StringBuilder staged = new StringBuilder("emp_number,date,hours_regular,hours_overtime\n");
    int accepted = 0;
    for (int i = 0; i < lineCount; i++) {
      int lineNo = i + 1;
      String line = raw[i];
      if (hasHeader && i == 0) {
        continue;
      }
      if (line.isBlank()) {
        continue;
      }
      String[] f = line.split(",", -1);
      String empNumber = f.length > 0 ? f[0].trim() : "";
      String date = f.length > 1 ? f[1].trim() : "";
      String reg = f.length > 2 ? f[2].trim() : "";
      String ot = f.length > 3 ? f[3].trim() : "";
      if (f.length != 4) {
        results.add(
            rejected(
                lineNo,
                empNumber,
                date,
                reg,
                ot,
                null,
                ErrorCode.VALIDATION_FAILED,
                "Expected 4 fields, got " + f.length,
                null));
        continue;
      }
      TimeAttendanceLine dto = new TimeAttendanceLine();
      dto.setEmpNumber(empNumber);
      String badField = null;
      String badMessage = null;
      try {
        dto.setWorkDate(LocalDate.parse(date));
      } catch (DateTimeParseException e) {
        badField = "date";
        badMessage = "date must be YYYY-MM-DD";
      }
      BigDecimal regNum = decimal(reg);
      BigDecimal otNum = decimal(ot);
      if (badField == null && regNum == null) {
        badField = "hours_regular";
        badMessage = "hours_regular must be a number 0.00-24.00";
      }
      if (badField == null && otNum == null) {
        badField = "hours_overtime";
        badMessage = "hours_overtime must be a number 0.00-24.00";
      }
      dto.setHoursRegular(regNum);
      dto.setHoursOvertime(otNum);
      Emp emp = employees.get(empNumber);
      if (badField == null) {
        List<ConstraintViolation<TimeAttendanceLine>> violations =
            new ArrayList<>(validator.validate(dto));
        violations.sort(Comparator.comparing(v -> v.getPropertyPath().toString()));
        if (!violations.isEmpty()) {
          ConstraintViolation<TimeAttendanceLine> v = violations.get(0);
          badField = v.getPropertyPath().toString();
          badMessage = v.getMessage();
        }
      }
      if (badField != null) {
        results.add(
            rejected(
                lineNo,
                empNumber,
                date,
                reg,
                ot,
                emp,
                ErrorCode.VALIDATION_FAILED,
                badMessage,
                badField));
        continue;
      }
      if (emp == null || !emp.active()) {
        results.add(
            rejected(
                lineNo,
                empNumber,
                date,
                reg,
                ot,
                emp,
                ErrorCode.EMPLOYEE_NOT_FOUND,
                "Invalid or inactive employee: " + empNumber,
                "emp_number"));
        continue;
      }
      String key = empNumber + "|" + dto.getWorkDate();
      if (!seen.add(key)) {
        results.add(
            rejected(
                lineNo,
                empNumber,
                date,
                reg,
                ot,
                emp,
                ErrorCode.DUPLICATE_ATTENDANCE_LINE,
                String.format(
                    ErrorCode.DUPLICATE_ATTENDANCE_LINE.defaultMessage(),
                    empNumber,
                    dto.getWorkDate()),
                "emp_number"));
        continue;
      }
      String regTxt = hours(regNum);
      String otTxt = hours(otNum);
      results.add(
          new TimeAttendanceLineResult(
              lineNo,
              empNumber,
              emp.empId(),
              dto.getWorkDate().toString(),
              regTxt,
              otTxt,
              emp.overtimeEligible(),
              "ACCEPTED",
              null));
      staged
          .append(empNumber)
          .append(',')
          .append(dto.getWorkDate())
          .append(',')
          .append(regTxt)
          .append(',')
          .append(otTxt)
          .append('\n');
      accepted++;
    }
    int rejectedCount = results.size() - accepted;
    if (accepted == 0) {
      List<ApiError.Detail> details = new ArrayList<>();
      for (TimeAttendanceLineResult r : results) {
        if (details.size() == 100) {
          break;
        }
        ApiError err = r.error();
        if (err != null) {
          details.add(new ApiError.Detail("line " + r.line(), err.code(), err.message()));
        }
      }
      throw new HrmsException(
          ErrorCode.IMPORT_REJECTED,
          String.format(ErrorCode.IMPORT_REJECTED.defaultMessage(), rejectedCount),
          "file",
          details,
          null);
    }
    String safeName = originalName.replaceAll("[^A-Za-z0-9._-]", "_");
    Artefact artefact =
        new Artefact(
            "TIME_ATTENDANCE",
            "TIME_ATTENDANCE_" + safeName,
            "STAGED",
            IntegrationFileRepository.utf8(staged.toString()),
            accepted,
            originalName,
            "text/csv",
            rejectedCount == 0 ? null : rejectedCount + " line(s) rejected");
    return new Outcome(artefact, results, accepted);
  }

  private Map<String, Emp> employees() {
    Map<String, Emp> out = new HashMap<>();
    jdbc.query(
        """
        select e.emp_number, e.emp_id, e.employment_status, g.overtime_eligible
          from employees e
          left join job_titles j on j.job_id = e.job_id
          left join job_grades g on g.grade_id = j.grade_id
        """,
        rs -> {
          String ot = rs.getString("overtime_eligible");
          out.put(
              rs.getString("emp_number"),
              new Emp(
                  rs.getLong("emp_id"),
                  "ACTIVE".equals(rs.getString("employment_status")),
                  ot == null ? null : "Y".equals(ot)));
        });
    return out;
  }

  private static TimeAttendanceLineResult rejected(
      int line,
      String empNumber,
      String date,
      String reg,
      String ot,
      @Nullable Emp emp,
      ErrorCode code,
      String message,
      @Nullable String field) {
    return new TimeAttendanceLineResult(
        line,
        empNumber,
        emp == null ? null : emp.empId(),
        date,
        reg,
        ot,
        emp == null ? null : emp.overtimeEligible(),
        "REJECTED",
        new ApiError(code.value(), message, field, TraceContext.currentTraceId(), null));
  }

  @Nullable
  private static BigDecimal decimal(String s) {
    if (!s.matches("^[0-9]{1,2}(\\.[0-9]{1,2})?$")) {
      return null;
    }
    return new BigDecimal(s);
  }

  private static String hours(BigDecimal v) {
    return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }
}
