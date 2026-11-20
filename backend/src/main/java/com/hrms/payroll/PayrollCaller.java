package com.hrms.payroll;

import com.hrms.auth.HrmsPrincipal;

/** Who performs a payroll command: a user (D-04 actor, IP and session for audit) or a job ({@code system:<job>}). */
public record PayrollCaller(String actor, String ipAddress, Long sessionId) {

    public static PayrollCaller of(HrmsPrincipal p, String ipAddress) {
        return new PayrollCaller(p.actor(), ipAddress, p.sessionId());
    }

    public static PayrollCaller system(String job) {
        return new PayrollCaller("system:" + job, null, null);
    }
}
