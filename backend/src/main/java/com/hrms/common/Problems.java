package com.hrms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Builds the D-05 error body: problem+json with {@code code}, {@code legacyCode}, {@code traceId}. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, Integer legacyCode, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:hrms:problem:" + code));
        p.setProperty("code", code);
        p.setProperty("legacyCode", legacyCode);
        p.setProperty("traceId", UUID.randomUUID().toString());
        return p;
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        return of(code.status(), code.name(), code.legacyCode(), detail);
    }

    public static ProblemDetail withErrors(ProblemDetail p, List<Map<String, String>> errors) {
        p.setProperty("errors", errors);
        return p;
    }

    /** Writes a problem from servlet filters / security handlers, outside Spring MVC. */
    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code, String detail)
            throws IOException {
        ProblemDetail p = of(code, detail);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), p);
    }
}
