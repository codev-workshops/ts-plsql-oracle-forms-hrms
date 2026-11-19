package com.hrms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.platform.calendar.BusinessDayCalendar;
import com.hrms.platform.employee.EmployeeLookup;
import com.hrms.platform.notification.NotificationService;
import com.hrms.platform.param.ParameterService;
import com.hrms.platform.salary.SalaryRecordService;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import com.hrms.platform.validation.Validators;
import com.hrms.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PlatformServicesTest extends IntegrationTest {

    @Autowired BusinessDayCalendar calendar;
    @Autowired EmployeeLookup employees;
    @Autowired SalaryRecordService salaries;
    @Autowired PermissionService permissions;
    @Autowired ParameterService params;
    @Autowired NotificationService notifications;

    @Test
    void schemaHasAllLegacyTablesViewsAndSeed() {
        Integer tables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                + " AND table_name NOT IN ('flyway_schema_history', 'user_credentials')", Integer.class);
        Integer views = jdbc.queryForObject("SELECT count(*) FROM information_schema.views"
                + " WHERE table_schema = 'public'", Integer.class);
        assertThat(tables).isEqualTo(30);
        assertThat(views).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vw_org_hierarchy", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM vw_active_employees", Integer.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE extract(year FROM holiday_date) = 2026",
                Integer.class)).isGreaterThanOrEqualTo(11);
    }

    @Test
    void identityColumnsStartAboveSeededIds() {
        Long deptId = jdbc.queryForObject("INSERT INTO departments (dept_code, dept_name, created_by)"
                + " VALUES ('TST', 'Test', 'T') RETURNING dept_id", Long.class);
        assertThat(deptId).isGreaterThanOrEqualTo(100L);
    }

    @Test
    void businessDaysSkipWeekendsAndHolidays() {
        // Thanksgiving week 2026: Thu 26 + Fri 27 are holidays
        assertThat(calendar.businessDays(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 29), "HQ")).isEqualTo(3);
        assertThat(calendar.businessDays(LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 11), "HQ")).isEqualTo(5);
        // SF-only holiday
        assertThat(calendar.businessDays(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31), "SF")).isZero();
        assertThat(calendar.businessDays(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31), "HQ")).isEqualTo(1);
    }

    @Test
    void employeeLookupResolvesManagersAndReports() {
        assertThat(employees.findById(22)).get().extracting(e -> e.managerEmpId()).isEqualTo(21L);
        assertThat(employees.directReports(21)).extracting(e -> e.empId()).contains(22L, 23L, 24L);
        assertThat(employees.isManagerOf(21, 22)).isTrue();
        assertThat(employees.findActiveByEmail("THOMAS.BAKER@company.com")).isPresent();
    }

    @Test
    void salaryChangeClosesPreviousRecord() {
        BigDecimal before = salaries.current(22).orElseThrow().baseSalary();
        long id = salaries.changeSalary(22, before.add(BigDecimal.valueOf(5000)), LocalDate.of(2026, 12, 1),
                "MERIT", null, "test");
        assertThat(salaries.current(22)).get().extracting(s -> s.salaryId()).isEqualTo(id);
        assertThat(salaries.history(22)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(salaries.history(22).get(1).endDate()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void permissionMatrixMatchesLegacyRules() {
        assertThat(permissions.hasPermission(8, Module.PAYROLL, Action.APPROVE)).isTrue();
        assertThat(permissions.hasPermission(5, Module.PAYROLL, Action.VIEW)).isTrue();
        assertThat(permissions.hasPermission(4, Module.PAYROLL, Action.VIEW)).isFalse();
        assertThat(permissions.hasPermission(1, Module.LEAVE, Action.CREATE)).isTrue();
        assertThat(permissions.hasPermission(1, Module.EMPLOYEE, Action.VIEW)).isTrue();
        assertThat(permissions.hasPermission(7, Module.EMPLOYEE, Action.EDIT)).isFalse();
    }

    @Test
    void parametersAndValidators() {
        assertThat(params.getInt("SECURITY", "SESSION_TIMEOUT_MIN", 0)).isEqualTo(30);
        assertThat(params.getInt("MISSING", "X", 7)).isEqualTo(7);
        assertThat(Validators.isValidEmail("a.b@company.com")).isTrue();
        assertThat(Validators.isValidEmail("nope")).isFalse();
        assertThat(Validators.dateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))).isPresent();
        assertThat(Validators.passwordPolicyViolation("Abcdefg1", 8)).isEmpty();
    }

    @Test
    void notificationsQueueAndDispatch() {
        notifications.queue(22, "Subject", "Body", "TEST", 1L, "test");
        assertThat(notifications.dispatchPending()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_queue WHERE status = 'PENDING'",
                Integer.class)).isZero();
    }

    @Test
    void holidayAndLookupEndpoints() throws Exception {
        String token = login("thomas.baker@company.com");
        call(get("/api/v1/holidays").param("year", "2026"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holidayName").value("New Year's Day"));
        call(get("/api/v1/lookups/leave_status"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PENDING"));
    }
}
