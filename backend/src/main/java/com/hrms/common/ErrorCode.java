package com.hrms.common;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes of the API contract (checkpoint D-05), frozen in contract v1. Every code has exactly one
 * HTTP status (amendment A-P3-02). Codes of later phases are catalogued here so they need no amendment.
 */
public enum ErrorCode {
    // Shared / auth (P2)
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, -20001),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, -20301),
    INVALID_CURRENT_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY, null),
    PASSWORD_POLICY(HttpStatus.BAD_REQUEST, null),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, null),
    FORBIDDEN(HttpStatus.FORBIDDEN, null),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, null),
    NOT_FOUND(HttpStatus.NOT_FOUND, null),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, null),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, null),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, null),
    // Employee core (implemented in P5; frozen in v1)
    EMPLOYEE_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, -20001),
    EMP_NUMBER_CONFLICT(HttpStatus.CONFLICT, -20002),
    INVALID_DEPARTMENT(HttpStatus.UNPROCESSABLE_ENTITY, -20003),
    INVALID_MANAGER(HttpStatus.UNPROCESSABLE_ENTITY, -20004),
    CIRCULAR_REPORTING(HttpStatus.UNPROCESSABLE_ENTITY, -20004),
    ALREADY_TERMINATED(HttpStatus.CONFLICT, -20005),
    INVALID_JOB(HttpStatus.UNPROCESSABLE_ENTITY, -20011),
    HIRE_DATE_TOO_FAR(HttpStatus.UNPROCESSABLE_ENTITY, -20501),
    EMAIL_IN_USE(HttpStatus.CONFLICT, -20502),
    REACTIVATION_FORBIDDEN(HttpStatus.CONFLICT, -20503),
    DELETE_FORBIDDEN(HttpStatus.METHOD_NOT_ALLOWED, -20504),
    // Added by contract amendment v1.1-P5: rehire of an employee who is not TERMINATED (DEF-13)
    EMPLOYEE_NOT_TERMINATED(HttpStatus.CONFLICT, null),
    // Added by contract amendment v1.5 (P8): unknown, used, superseded or expired password reset token (OQ-15)
    INVALID_RESET_TOKEN(HttpStatus.UNPROCESSABLE_ENTITY, null),
    // Payroll (P6)
    INVALID_SALARY(HttpStatus.UNPROCESSABLE_ENTITY, -20101),
    PERIOD_CLOSED(HttpStatus.CONFLICT, -20102),
    INVALID_RUN_STATUS(HttpStatus.CONFLICT, -20103),
    NO_ACTIVE_SALARY(HttpStatus.UNPROCESSABLE_ENTITY, -20104),
    // Leave (P3)
    INSUFFICIENT_LEAVE_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, -20201),
    LEAVE_OVERLAP(HttpStatus.CONFLICT, -20202),
    INVALID_LEAVE_TYPE(HttpStatus.UNPROCESSABLE_ENTITY, -20203),
    MIN_TENURE_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, -20203),
    INVALID_REQUEST_STATUS(HttpStatus.CONFLICT, -20204),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, -20210),
    BACKDATE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, -20211),
    NO_BUSINESS_DAYS(HttpStatus.UNPROCESSABLE_ENTITY, -20212),
    // Performance (P4)
    INVALID_CYCLE_STATUS(HttpStatus.CONFLICT, -20401),
    INVALID_REVIEW_STATUS(HttpStatus.CONFLICT, -20402),
    INVALID_RATING(HttpStatus.BAD_REQUEST, -20403),
    // System parameters (P7)
    PARAM_NOT_EDITABLE(HttpStatus.CONFLICT, -20900);

    private final HttpStatus status;
    private final Integer legacyCode;

    ErrorCode(HttpStatus status, Integer legacyCode) {
        this.status = status;
        this.legacyCode = legacyCode;
    }

    public HttpStatus status() {
        return status;
    }

    public Integer legacyCode() {
        return legacyCode;
    }
}
