package com.hrms.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Stable API error codes. Legacy RAISE_APPLICATION_ERROR numbers are kept verbatim
 * (open question 10); new modern-only codes use unused numbers in the owning range.
 * Phase 3 modules add codes in their own package (see slice-recipe.md), not here.
 */
public enum ErrorCode {
    VALIDATION("-20000", HttpStatus.BAD_REQUEST),
    EMPLOYEE_NOT_FOUND("-20001", HttpStatus.NOT_FOUND),
    NOT_FOUND("-20002", HttpStatus.NOT_FOUND),

    LEAVE_INSUFFICIENT_BALANCE("-20201", HttpStatus.UNPROCESSABLE_ENTITY),
    LEAVE_OVERLAP("-20202", HttpStatus.CONFLICT),
    LEAVE_INVALID_TYPE("-20203", HttpStatus.UNPROCESSABLE_ENTITY),
    LEAVE_WRONG_STATUS("-20204", HttpStatus.CONFLICT),
    LEAVE_NOT_APPROVER("-20205", HttpStatus.FORBIDDEN),
    LEAVE_NOT_OWNER("-20206", HttpStatus.FORBIDDEN),
    LEAVE_START_AFTER_END("-20210", HttpStatus.UNPROCESSABLE_ENTITY),
    LEAVE_BACKDATED("-20211", HttpStatus.UNPROCESSABLE_ENTITY),
    LEAVE_NO_BUSINESS_DAYS("-20212", HttpStatus.UNPROCESSABLE_ENTITY),

    AUTH_INVALID_CREDENTIALS("-20301", HttpStatus.UNAUTHORIZED),
    AUTH_ACCOUNT_LOCKED("-20302", HttpStatus.UNAUTHORIZED),
    AUTH_SESSION_EXPIRED("-20303", HttpStatus.UNAUTHORIZED),
    AUTH_INSUFFICIENT_PRIVILEGE("-20304", HttpStatus.FORBIDDEN),
    PASSWORD_TOO_SHORT("-20310", HttpStatus.BAD_REQUEST),
    PASSWORD_NO_UPPERCASE("-20311", HttpStatus.BAD_REQUEST),
    PASSWORD_NO_DIGIT("-20312", HttpStatus.BAD_REQUEST),

    PARAMETER_NOT_FOUND("-20900", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL("-20999", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus status;

    ErrorCode(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
