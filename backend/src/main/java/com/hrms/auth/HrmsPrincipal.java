package com.hrms.auth;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * The request-scoped authenticated principal. Replaces the Forms globals
 * ({@code :GLOBAL.session_id/current_user/current_emp_id}) and PKG_EMPLOYEE.set_session_context.
 */
public record HrmsPrincipal(long empId, String username, long sessionId, OffsetDateTime loginTime,
        OffsetDateTime expiresAt, Set<Permission> permissions) {

    /** The D-04 actor value written to created_by / modified_by / changed_by. */
    public String actor() {
        return username;
    }

    public boolean has(Module module, Action action) {
        return permissions.contains(new Permission(module, action));
    }
}
