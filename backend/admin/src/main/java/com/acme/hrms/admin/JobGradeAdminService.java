package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.JobGrade;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.JobGradeRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner of JOB_GRADES (CHK_SALARY_RANGE enforced here, -20603). */
@Service
public class JobGradeAdminService {
  private static final String SELECT =
      """
      select g.*, (select count(*) from job_titles t
                    where t.grade_id = g.grade_id and t.active_flag = 'Y') as active_job_titles
        from job_grades g
      """;

  private static final RowMapper<JobGrade> MAPPER =
      (rs, i) ->
          new JobGrade(
              rs.getInt("grade_id"),
              rs.getString("grade_code"),
              rs.getString("grade_name"),
              AdminDtos.money(rs.getBigDecimal("min_salary")),
              AdminDtos.money(rs.getBigDecimal("max_salary")),
              AdminSupport.flag(rs.getString("overtime_eligible")),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("active_job_titles"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final AdminSupport s;

  public JobGradeAdminService(AdminSupport s) {
    this.s = s;
  }

  public List<JobGrade> list(@Nullable Boolean active) {
    return s.jdbc()
        .query(
            SELECT
                + " where 1=1"
                + AdminSupport.activeWhere(active, "g.active_flag")
                + " order by g.grade_code",
            Map.of(),
            MAPPER);
  }

  public JobGrade get(int gradeId) {
    List<JobGrade> rows =
        s.jdbc().query(SELECT + " where g.grade_id = :id", Map.of("id", gradeId), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public JobGrade create(JobGradeRequest r) {
    if (s.count(
            "select count(*) from job_grades where grade_code = :c", Map.of("c", r.getGradeCode()))
        > 0) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "gradeCode", r.getGradeCode());
    }
    checkRange(r);
    int id =
        Objects.requireNonNull(
            s.jdbc().queryForObject("select nextval('seq_job_grade')", Map.of(), Integer.class));
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into job_grades (grade_id, grade_code, grade_name, min_salary, max_salary,
                                    overtime_eligible, active_flag, created_by, created_date)
            values (:id, :code, :name, :min, :max, :ot, :active, :actor, :now)
            """,
            p);
    JobGrade created = get(id);
    s.audit("JOB_GRADES", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public JobGrade update(int gradeId, JobGradeRequest r) {
    JobGrade old = get(gradeId);
    if (!old.gradeCode().equals(r.getGradeCode())) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "gradeCode", r.getGradeCode());
    }
    checkRange(r);
    Map<String, Object> p = params(r);
    p.put("id", gradeId);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            update job_grades
               set grade_name = :name, min_salary = :min, max_salary = :max,
                   overtime_eligible = :ot, active_flag = :active,
                   modified_by = :actor, modified_date = :now
             where grade_id = :id
            """,
            p);
    JobGrade updated = get(gradeId);
    s.audit("JOB_GRADES", gradeId, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(int gradeId) {
    JobGrade old = get(gradeId);
    if (!old.activeFlag()) {
      return;
    }
    if (old.activeJobTitles() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Job grade",
          old.gradeCode(),
          old.activeJobTitles(),
          "job titles");
    }
    s.jdbc()
        .update(
            "update job_grades set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where grade_id = :id",
            Map.of("id", gradeId, "actor", s.actor(), "now", s.now()));
    s.audit("JOB_GRADES", gradeId, Action.STATUS_CHANGE, old, get(gradeId));
  }

  private static void checkRange(JobGradeRequest r) {
    if (r.getMaxSalary().compareTo(r.getMinSalary()) < 0) {
      throw new HrmsException(
          ErrorCode.REFERENCE_VALUE_RULE,
          "Maximum salary must be greater than or equal to minimum salary",
          "maxSalary");
    }
  }

  private static Map<String, Object> params(JobGradeRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getGradeCode());
    p.put("name", r.getGradeName());
    p.put("min", r.getMinSalary());
    p.put("max", r.getMaxSalary());
    p.put("ot", AdminSupport.flag(r.getOvertimeEligible(), false));
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
