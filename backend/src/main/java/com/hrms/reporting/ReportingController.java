package com.hrms.reporting;

import static com.hrms.reporting.ReportingModels.Report;

import com.hrms.platform.auth.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportingService reporting;
    private final ContentNegotiationManager negotiation;

    public ReportingController(ReportingService reporting, ContentNegotiationManager negotiation) {
        this.reporting = reporting;
        this.negotiation = negotiation;
    }

    @GetMapping("/headcount")
    ResponseEntity<?> headcount(CurrentUser user, NativeWebRequest request,
                                @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
                                @RequestParam(required = false) Long deptId,
                                @RequestParam(required = false) Long locationId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "headcount", reporting.headcount(user, asOf, deptId, locationId));
    }

    @GetMapping("/compensation")
    ResponseEntity<?> compensation(CurrentUser user, NativeWebRequest request,
                                   @RequestParam(required = false) Long deptId,
                                   @RequestParam(required = false) Long gradeId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "compensation", reporting.compensation(user, deptId, gradeId));
    }

    @GetMapping("/turnover")
    ResponseEntity<?> turnover(CurrentUser user, NativeWebRequest request,
                               @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
                               @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate endDate,
                               @RequestParam(required = false) Long deptId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "turnover", reporting.turnover(user, startDate, endDate, deptId));
    }

    @GetMapping("/new-hires")
    ResponseEntity<?> newHires(CurrentUser user, NativeWebRequest request,
                               @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
                               @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate endDate,
                               @RequestParam(required = false) Long deptId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "new-hires", reporting.newHires(user, startDate, endDate, deptId));
    }

    @GetMapping("/leave-utilization")
    ResponseEntity<?> leaveUtilization(CurrentUser user, NativeWebRequest request, @RequestParam int year,
                                       @RequestParam(required = false) Long deptId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "leave-utilization", reporting.leaveUtilization(user, year, deptId));
    }

    @GetMapping("/payroll-summary")
    ResponseEntity<?> payrollSummary(CurrentUser user, NativeWebRequest request, @RequestParam long periodId)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "payroll-summary", reporting.payrollSummary(user, periodId));
    }

    @GetMapping("/eeo")
    ResponseEntity<?> eeo(CurrentUser user, NativeWebRequest request,
                          @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf)
            throws HttpMediaTypeNotAcceptableException {
        return respond(request, "eeo", reporting.eeo(user, asOf));
    }

    private ResponseEntity<?> respond(NativeWebRequest request, String slug, Report report)
            throws HttpMediaTypeNotAcceptableException {
        var response = ResponseEntity.ok().varyBy("Accept").cacheControl(CacheControl.noStore());
        for (MediaType accepted : negotiation.resolveMediaTypes(request)) {
            if (accepted.getQualityValue() == 0) {
                continue;
            }
            if (accepted.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return response.contentType(MediaType.APPLICATION_JSON).body(report);
            }
            if (accepted.isCompatibleWith(CSV)) {
                return response.contentType(CSV)
                        .headers(headers -> headers.setContentDisposition(
                                ContentDisposition.attachment().filename(slug + ".csv").build()))
                        .body(ReportCsv.encode(report));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
}
