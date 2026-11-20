package com.hrms.auth;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HRMS_MENU backend (FRM-010…FRM-015, MNU-005, MNU-007): each entry carries the legacy gate result.
 * Employee, Leave and Performance were ungated in Forms (FRM-014); Payroll and Reports need
 * {@code <module>:VIEW}. System Admin is hidden (OQ-03). Navigation targets are owned by P7.
 */
@RestController
public class MenuController {

    /**
     * {@code route} is the client navigation target and {@code apiPath} the API entry point it uses (P7,
     * contract v1.4). Admin is not returned (OQ-03); its only kept function is CHANGE_PASSWORD.
     */
    public record MenuEntry(String id, String label, Module module, boolean enabled, String route,
            String apiPath) {
    }

    public record Menu(List<MenuEntry> modules, List<MenuEntry> account) {
    }

    @GetMapping("/api/v1/menu")
    Menu menu(@AuthenticationPrincipal HrmsPrincipal p) {
        return new Menu(List.of(
                new MenuEntry("EMPLOYEES", "Employee Management", Module.EMPLOYEE, true,
                        "/employees", "/api/v1/employees"),
                new MenuEntry("PAYROLL", "Payroll", Module.PAYROLL, p.has(Module.PAYROLL, Action.VIEW),
                        "/payroll", "/api/v1/payroll/runs"),
                new MenuEntry("LEAVE", "Leave Management", Module.LEAVE, true,
                        "/leave", "/api/v1/leave/requests"),
                new MenuEntry("PERFORMANCE", "Performance", Module.PERFORMANCE, true,
                        "/performance", "/api/v1/performance/reviews"),
                new MenuEntry("REPORTS", "Reports & Analytics", Module.REPORTS, p.has(Module.REPORTS, Action.VIEW),
                        "/reports", "/api/v1/reports")),
                List.of(new MenuEntry("CHANGE_PASSWORD", "Change Password", null, true,
                                "/account/password", "/api/v1/auth/change-password"),
                        new MenuEntry("LOGOUT", "Logout", null, true, null, "/api/v1/auth/logout")));
    }
}
