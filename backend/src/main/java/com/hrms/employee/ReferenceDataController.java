package com.hrms.employee;

import com.hrms.employee.EmployeeModels.HeadcountRow;
import com.hrms.employee.EmployeeModels.RefItem;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReferenceDataController {

    private final EmployeeRepository repo;
    private final PermissionService permissions;
    private final Clock clock;

    public ReferenceDataController(EmployeeRepository repo, PermissionService permissions, Clock clock) {
        this.repo = repo;
        this.permissions = permissions;
        this.clock = clock;
    }

    @GetMapping("/departments")
    public List<RefItem> departments(CurrentUser user) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        return repo.departments();
    }

    @GetMapping("/departments/headcount")
    public List<HeadcountRow> headcount(CurrentUser user, @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        return repo.headcount(asOf != null ? asOf : LocalDate.now(clock));
    }

    @GetMapping("/locations")
    public List<RefItem> locations(CurrentUser user) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        return repo.locations();
    }

    @GetMapping("/job-titles")
    public List<RefItem> jobTitles(CurrentUser user) {
        permissions.require(user, Module.EMPLOYEE, Action.VIEW);
        return repo.jobTitles();
    }
}
