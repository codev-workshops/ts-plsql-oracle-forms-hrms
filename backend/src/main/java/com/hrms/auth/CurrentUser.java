package com.hrms.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** The "current principal" view (re-homes the HRMS_LOGIN globals, FRM-002, and the HRMS_MENU gates' data). */
public record CurrentUser(long empId, String empNumber, String username, String firstName, String lastName,
        OffsetDateTime loginTime, OffsetDateTime expiresAt, Map<Module, List<Action>> permissions) {
}
