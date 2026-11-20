package com.hrms.employee;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Active-only lookups replacing the HRMS_EMPLOYEE record groups RG_DEPARTMENTS/RG_JOB_TITLES/RG_LOCATIONS (FRM-046). */
@RestController
@RequestMapping("/api/v1/lookups")
@PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
public class LookupController {

    public record Department(long deptId, String deptCode, String deptName, Long parentDeptId, String locationCode) {
    }

    public record JobTitle(long jobId, String jobCode, String jobTitle, long gradeId) {
    }

    public record Location(String locationCode, String locationName, String city, String timezone) {
    }

    private final JdbcClient jdbc;

    public LookupController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/departments")
    List<Department> departments() {
        return jdbc.sql("""
                SELECT dept_id, dept_code, dept_name, parent_dept_id, location_code FROM hrms.departments
                WHERE active_flag ORDER BY dept_name, dept_id
                """).query((rs, n) -> new Department(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5))).list();
    }

    @GetMapping("/job-titles")
    List<JobTitle> jobTitles() {
        return jdbc.sql("""
                SELECT job_id, job_code, job_title, grade_id FROM hrms.job_titles
                WHERE active_flag ORDER BY job_title, job_id
                """).query((rs, n) -> new JobTitle(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                .list();
    }

    @GetMapping("/locations")
    List<Location> locations() {
        return jdbc.sql("""
                SELECT location_code, location_name, city, timezone FROM hrms.locations
                WHERE active_flag ORDER BY location_name, location_code
                """).query((rs, n) -> new Location(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }
}
