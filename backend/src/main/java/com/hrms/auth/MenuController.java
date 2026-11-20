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

    public record MenuEntry(String id, String label, Module module, boolean enabled) {
    }

    public record Menu(List<MenuEntry> modules, List<MenuEntry> account) {
    }

    @GetMapping("/api/v1/menu")
    Menu menu(@AuthenticationPrincipal HrmsPrincipal p) {
        return new Menu(List.of(
                new MenuEntry("EMPLOYEES", "Employee Management", Module.EMPLOYEE, true),
                new MenuEntry("PAYROLL", "Payroll", Module.PAYROLL, p.has(Module.PAYROLL, Action.VIEW)),
                new MenuEntry("LEAVE", "Leave Management", Module.LEAVE, true),
                new MenuEntry("PERFORMANCE", "Performance", Module.PERFORMANCE, true),
                new MenuEntry("REPORTS", "Reports & Analytics", Module.REPORTS, p.has(Module.REPORTS, Action.VIEW))),
                List.of(new MenuEntry("CHANGE_PASSWORD", "Change Password", null, true),
                        new MenuEntry("LOGOUT", "Logout", null, true)));
    }
}
