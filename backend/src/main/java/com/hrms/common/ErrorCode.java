package com.hrms.common;

import org.springframework.http.HttpStatus;

/** Stable error codes of the API contract (checkpoint D-05). */
public enum ErrorCode {
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
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, null);

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
