package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.JobTitle;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.JobTitleRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner of JOB_TITLES (-20604 on an inactive grade). */
@Service
public class JobTitleAdminService {
  private static final String SELECT =
      """
      select t.*, g.grade_code,
             (select count(*) from employees e
               where e.job_id = t.job_id and e.employment_status = 'ACTIVE') as active_employees
        from job_titles t
        join job_grades g on g.grade_id = t.grade_id
      """;

  private static final RowMapper<JobTitle> MAPPER =
      (rs, i) ->
          new JobTitle(
              rs.getLong("job_id"),
              rs.getString("job_code"),
              rs.getString("job_title"),
              rs.getString("job_family"),
              rs.getInt("grade_id"),
              rs.getString("grade_code"),
              rs.getString("eeo_category"),
              rs.getString("flsa_status"),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("active_employees"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final AdminSupport s;

  public JobTitleAdminService(AdminSupport s) {
    this.s = s;
  }

  public List<JobTitle> list(@Nullable Boolean active) {
    return s.jdbc()
        .query(
            SELECT
                + " where 1=1"
                + AdminSupport.activeWhere(active, "t.active_flag")
                + " order by t.job_code",
            Map.of(),
            MAPPER);
  }

  public JobTitle get(long jobId) {
    List<JobTitle> rows =
        s.jdbc().query(SELECT + " where t.job_id = :id", Map.of("id", jobId), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public JobTitle create(JobTitleRequest r) {
    if (s.count("select count(*) from job_titles where job_code = :c", Map.of("c", r.getJobCode()))
        > 0) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "jobCode", r.getJobCode());
    }
    s.requireActiveGrade(r.getGradeId());
    long id =
        Objects.requireNonNull(
            s.jdbc().queryForObject("select nextval('seq_job_title')", Map.of(), Long.class));
    Map<String, Object> p = params(r);
    p.put("id", id);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into job_titles (job_id, job_code, job_title, job_family, grade_id, eeo_category,
                                    flsa_status, active_flag, created_by, created_date)
            values (:id, :code, :title, :family, :grade, :eeo, :flsa, :active, :actor, :now)
            """,
            p);
    JobTitle created = get(id);
    s.audit("JOB_TITLES", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public JobTitle update(long jobId, JobTitleRequest r) {
    JobTitle old = get(jobId);
    if (!old.jobCode().equals(r.getJobCode())) {
      throw AdminSupport.error(ErrorCode.REFERENCE_CODE_CONFLICT, "jobCode", r.getJobCode());
    }
    s.requireActiveGrade(r.getGradeId());
    Map<String, Object> p = params(r);
    p.put("id", jobId);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            update job_titles
               set job_title = :title, job_family = :family, grade_id = :grade,
                   eeo_category = :eeo, flsa_status = :flsa, active_flag = :active,
                   modified_by = :actor, modified_date = :now
             where job_id = :id
            """,
            p);
    JobTitle updated = get(jobId);
    s.audit("JOB_TITLES", jobId, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(long jobId) {
    JobTitle old = get(jobId);
    if (!old.activeFlag()) {
      return;
    }
    if (old.activeEmployees() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Job title",
          old.jobCode(),
          old.activeEmployees(),
          "employees");
    }
    s.jdbc()
        .update(
            "update job_titles set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where job_id = :id",
            Map.of("id", jobId, "actor", s.actor(), "now", s.now()));
    s.audit("JOB_TITLES", jobId, Action.STATUS_CHANGE, old, get(jobId));
  }

  private static Map<String, Object> params(JobTitleRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getJobCode());
    p.put("title", r.getJobTitle());
    p.put("family", r.getJobFamily());
    p.put("grade", r.getGradeId());
    p.put("eeo", r.getEeoCategory());
    p.put("flsa", r.getFlsaStatus() == null ? "EXEMPT" : r.getFlsaStatus());
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
