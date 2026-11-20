package com.hrms.common;

/** A business or contract error rendered as RFC 9457 problem+json by {@link ProblemHandler}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Integer legacyCode;

    public ApiException(ErrorCode code, String message) {
        this(code, message, code.legacyCode());
    }

    public ApiException(ErrorCode code, String message, Integer legacyCode) {
        super(message);
        this.code = code;
        this.legacyCode = legacyCode;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer legacyCode() {
        return legacyCode;
    }
}
