package com.hrms.reporting;

import static com.hrms.reporting.ReportingModels.Report;
import static com.hrms.reporting.ReportingModels.Table;

import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.employee.LocationLookup;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import com.hrms.platform.security.Action;
import com.hrms.platform.security.Module;
import com.hrms.platform.security.PermissionService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class ReportingService {

    private final ReportingRepository reports;
    private final PermissionService permissions;
    private final LocationLookup locations;
    private final Clock clock;

    public ReportingService(ReportingRepository reports, PermissionService permissions,
                            LocationLookup locations, Clock clock) {
        this.reports = reports;
        this.permissions = permissions;
        this.locations = locations;
        this.clock = clock;
    }

    Report headcount(CurrentUser user, LocalDate asOf, Long deptId, Long locationId) {
        return generate(user, "Headcount", () -> {
            LocalDate date = asOfOrToday(asOf);
            String locationCode = locationId == null ? null : locations.codeForId(locationId).orElse(null);
            return reports.headcount(date, deptId, locationCode, locationId != null);
        });
    }

    Report compensation(CurrentUser user, Long deptId, Long gradeId) {
        return generate(user, "Compensation", () -> reports.compensation(deptId, gradeId));
    }

    Report turnover(CurrentUser user, LocalDate startDate, LocalDate endDate, Long deptId) {
        return generate(user, "Turnover", () -> {
            requireDateRange(startDate, endDate);
            return reports.turnover(startDate, endDate, deptId);
        });
    }

    Report newHires(CurrentUser user, LocalDate startDate, LocalDate endDate, Long deptId) {
        return generate(user, "New hires", () -> {
            requireDateRange(startDate, endDate);
            return reports.newHires(startDate, endDate, deptId);
        });
    }

    Report leaveUtilization(CurrentUser user, int year, Long deptId) {
        return generate(user, "Leave utilization", () -> reports.leaveUtilization(year, deptId));
    }

    Report payrollSummary(CurrentUser user, long periodId) {
        return generate(user, "Payroll summary", () -> reports.payrollSummary(periodId));
    }

    Report eeo(CurrentUser user, LocalDate asOf) {
        return generate(user, "EEO compliance", () -> reports.eeo(asOfOrToday(asOf)));
    }

    private Report generate(CurrentUser user, String name, Supplier<Table> query) {
        permissions.require(user, Module.REPORTS, Action.VIEW);
        Table table = query.get();
        return new Report(name, LocalDateTime.now(clock), table.columns(), table.rows());
    }

    private LocalDate asOfOrToday(LocalDate asOf) {
        return asOf == null ? LocalDate.now(clock) : asOf;
    }

    private static void requireDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ApiException(ErrorCode.VALIDATION, "endDate must not be earlier than startDate");
        }
    }
}
