package com.acme.hrms.reference;

import com.acme.hrms.reference.ReferenceDtos.EmployeeSummary;
import com.acme.hrms.reference.ReferenceDtos.PageOfEmployeeSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/**
 * GET /api/employees, Phase 0 projection only ({@code fields=id,name,jobTitle}) - the searchable
 * manager LOV that replaces RG_MANAGERS / LOV_MANAGERS. Full employee search is Phase 3.
 */
@Repository
public class EmployeeSearchRepository {

  public static final int MAX_TOKENS = 3;

  private final JdbcTemplate jdbc;

  public EmployeeSearchRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param excludeEmpId caller's {@code jwt.empId} when {@code excludeSelf=true}, else null.
   */
  public PageOfEmployeeSummary searchActive(
      @Nullable String q, @Nullable Long excludeEmpId, int page, int size) {
    StringBuilder where =
        new StringBuilder(" where e.employment_status = 'ACTIVE' and e.active_flag = 'Y'");
    List<Object> args = new ArrayList<>();
    if (excludeEmpId != null) {
      where.append(" and e.emp_id <> ?");
      args.add(excludeEmpId);
    }
    for (String token : tokens(q)) {
      where.append(
          " and (lower(e.first_name) like ? or lower(e.last_name) like ?"
              + " or lower(e.first_name || ' ' || e.last_name) like ? or lower(e.emp_number) like ?)");
      String contains = "%" + escapeLike(token.toLowerCase(Locale.ROOT)) + "%";
      args.add(contains);
      args.add(contains);
      args.add(contains);
      args.add(contains);
    }
    String from = " from employees e left join job_titles j on j.job_id = e.job_id" + where;

    Long total = jdbc.queryForObject("select count(*)" + from, Long.class, args.toArray());
    long totalElements = total == null ? 0 : total;

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    List<EmployeeSummary> content =
        jdbc.query(
            "select e.emp_id, e.emp_number, e.first_name, e.last_name, j.job_title"
                + from
                + " order by e.last_name, e.first_name, e.emp_id limit ? offset ?",
            (rs, i) ->
                new EmployeeSummary(
                    rs.getLong("emp_id"),
                    rs.getString("emp_number"),
                    rs.getString("first_name") + " " + rs.getString("last_name"),
                    rs.getString("job_title")),
            pageArgs.toArray());

    int totalPages = (int) ((totalElements + size - 1) / size);
    return new PageOfEmployeeSummary(content, page, size, totalElements, totalPages);
  }

  static List<String> tokens(@Nullable String q) {
    if (q == null || q.isBlank()) {
      return List.of();
    }
    String[] parts = q.trim().split("\\s+");
    return List.of(parts).subList(0, Math.min(parts.length, MAX_TOKENS));
  }

  static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
