package com.hrms.platform.security;

import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Grade-level permission matrix ported from PKG_SECURITY.has_permission (open question 3):
 * grade >= 8 everything; VIEW with grade >= 5; LEAVE CREATE/VIEW and EMPLOYEE VIEW for all.
 * Record-level rules (own records, direct reports) are enforced by each module's service.
 */
@Service
public class PermissionService {

    public static final int FULL_ACCESS_GRADE = 8;
    public static final int VIEW_ALL_GRADE = 5;

    public boolean hasPermission(int gradeLevel, Module module, Action action) {
        if (gradeLevel >= FULL_ACCESS_GRADE) {
            return true;
        }
        if (action == Action.VIEW && gradeLevel >= VIEW_ALL_GRADE) {
            return true;
        }
        if (module == Module.LEAVE && (action == Action.CREATE || action == Action.VIEW)) {
            return true;
        }
        return module == Module.EMPLOYEE && action == Action.VIEW;
    }

    public boolean hasPermission(CurrentUser user, Module module, Action action) {
        return hasPermission(user.gradeLevel(), module, action);
    }

    public void require(CurrentUser user, Module module, Action action) {
        if (!hasPermission(user, module, action)) {
            throw new ApiException(ErrorCode.AUTH_INSUFFICIENT_PRIVILEGE,
                    "Insufficient privilege: " + module + "/" + action);
        }
    }
}
