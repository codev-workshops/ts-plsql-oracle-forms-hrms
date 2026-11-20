package com.hrms.auth;

import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import java.util.regex.Pattern;

/** PKG_SECURITY.change_password complexity rules, in legacy order (-20310, -20311, -20312). */
final class PasswordPolicy {

    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private PasswordPolicy() {
    }

    static void check(String password) {
        if (password.length() < 8) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY, "Password must be at least 8 characters", -20310);
        }
        if (!UPPER.matcher(password).find()) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY, "Password must contain an uppercase letter", -20311);
        }
        if (!DIGIT.matcher(password).find()) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY, "Password must contain a number", -20312);
        }
    }
}
