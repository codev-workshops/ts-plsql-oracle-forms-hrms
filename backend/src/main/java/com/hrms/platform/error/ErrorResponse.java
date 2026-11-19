package com.hrms.platform.error;

import java.util.List;

/** Error body shared by every endpoint (openapi-v1.yaml#/components/schemas/Error). */
public record ErrorResponse(String code, String message, List<String> details) {

    public ErrorResponse(String code, String message) {
        this(code, message, List.of());
    }
}
