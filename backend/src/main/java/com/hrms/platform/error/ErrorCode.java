package com.hrms.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Stable API error codes. Legacy RAISE_APPLICATION_ERROR numbers are kept verbatim
 * (inventory §4); modern-only codes use unused numbers (-209xx). Every legacy code of every
 * module is pre-registered here so Phase 3 slices never need to edit this shared file.
 */
public enum ErrorCode {
    VALIDATION("-20000", HttpStatus.BAD_REQUEST),

    // Employee (PKG_EMPLOYEE, employee triggers)
    EMPLOYEE_NOT_FOUND("-20001", HttpStatus.NOT_FOUND),
    EMPLOYEE_DUPLICATE_NUMBER("-20002", HttpStatus.CONFLICT),
    EMPLOYEE_INVALID_DEPARTMENT("-20003", HttpStatus.UNPROCESSABLE_ENTITY),
    EMPLOYEE_INVALID_MANAGER("-20004", HttpStatus.UNPROCESSABLE_ENTITY),
    EMPLOYEE_ALREADY_TERMINATED("-20005", HttpStatus.CONFLICT),
    EMPLOYEE_NAME_REQUIRED("-20010", HttpStatus.UNPROCESSABLE_ENTITY),
    EMPLOYEE_INVALID_JOB("-20011", HttpStatus.UNPROCESSABLE_ENTITY),
    EMPLOYEE_NOT_ACTIVE("-20012", HttpStatus.CONFLICT),
    EMPLOYEE_HIRE_DATE_TOO_FAR("-20501", HttpStatus.UNPROCESSABLE_ENTITY),
    EMPLOYEE_DUPLICATE_EMAIL("-20502", HttpStatus.CONFLICT),
    EMPLOYEE_DELETE_FORBIDDEN("-20504", HttpStatus.CONFLICT),

    // Payroll (PKG_PAYROLL)
    PAYROLL_INVALID_SALARY("-20101", HttpStatus.UNPROCESSABLE_ENTITY),
    PAYROLL_PERIOD_CLOSED("-20102", HttpStatus.CONFLICT),
    PAYROLL_WRONG_STATUS("-20103", HttpStatus.CONFLICT),
    PAYROLL_NO_ACTIVE_SALARY("-20104", HttpStatus.UNPROCESSABLE_ENTITY),

    // Leave (PKG_LEAVE)

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

    // Performance (PKG_PERFORMANCE)
    PERFORMANCE_CYCLE_NOT_DRAFT("-20401", HttpStatus.CONFLICT),
    PERFORMANCE_WRONG_STATUS("-20402", HttpStatus.CONFLICT),
    PERFORMANCE_INVALID_RATING("-20403", HttpStatus.UNPROCESSABLE_ENTITY),

    // Modern-only generic codes
    NOT_FOUND("-20990", HttpStatus.NOT_FOUND),
    CONFLICT("-20991", HttpStatus.CONFLICT),

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
