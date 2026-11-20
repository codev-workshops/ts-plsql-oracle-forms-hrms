package com.hrms.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Evaluates the data-driven permission matrix ({@code hrms.permission_rules}), replacing
 * PKG_SECURITY.has_permission. A rule with a NULL module or action matches every module or action;
 * a rule applies when the employee's job {@code grade_id} is at least {@code min_grade_id}. An
 * employee without a job/grade row gets nothing (legacy NO_DATA_FOUND → FALSE).
 */
@Service
public class PermissionService {

    private final JdbcClient jdbc;

    public PermissionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Permission> permissionsOf(long empId) {
        Optional<Long> grade = jdbc.sql("""
                SELECT j.grade_id FROM hrms.employees e JOIN hrms.job_titles j ON j.job_id = e.job_id
                WHERE e.emp_id = :empId
                """).param("empId", empId).query(Long.class).optional();
        if (grade.isEmpty()) {
            return Set.of();
        }
        List<String[]> rules = jdbc.sql("""
                SELECT module_code, action_code FROM hrms.permission_rules
                WHERE active_flag AND min_grade_id <= :grade
                """).param("grade", grade.get())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
        Set<Permission> out = new LinkedHashSet<>();
        for (String[] rule : rules) {
            Set<Module> modules = rule[0] == null ? EnumSet.allOf(Module.class) : EnumSet.of(Module.valueOf(rule[0]));
            Set<Action> actions = rule[1] == null ? EnumSet.allOf(Action.class) : EnumSet.of(Action.valueOf(rule[1]));
            for (Module m : modules) {
                for (Action a : actions) {
                    out.add(new Permission(m, a));
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public boolean hasPermission(long empId, Module module, Action action) {
        return permissionsOf(empId).contains(new Permission(module, action));
    }
}
