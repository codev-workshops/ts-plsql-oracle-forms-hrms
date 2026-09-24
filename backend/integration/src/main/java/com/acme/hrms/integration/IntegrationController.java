package com.acme.hrms.integration;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFile;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFilePage;
import com.acme.hrms.integration.IntegrationDtos.IntegrationStatus;
import com.acme.hrms.integration.IntegrationDtos.TimeAttendanceImportResult;
import com.acme.hrms.validation.dto.integration.BenefitsFeedRequest;
import com.acme.hrms.validation.dto.integration.GlFeedRequest;
import com.acme.hrms.validation.dto.integration.IntegrationFileListQuery;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/integration/*} routes of the frozen P5 contract. */
@RestController
public class IntegrationController {
  private final IntegrationService service;

  public IntegrationController(IntegrationService service) {
    this.service = service;
  }

  @PostMapping("/api/integration/gl-feed")
  @PreAuthorize("hasAuthority('PAYROLL:APPROVE')")
  public ResponseEntity<IntegrationFile> glFeed(@Valid @RequestBody GlFeedRequest body) {
    return created(service.generateGlFeed(body.getRunId()));
  }

  @PostMapping("/api/integration/benefits-feed")
  @PreAuthorize("hasAuthority('ADMIN:EDIT') and hasAuthority('EMPLOYEE:VIEW')")
  public ResponseEntity<IntegrationFile> benefitsFeed(
      @Valid @RequestBody(required = false) @Nullable BenefitsFeedRequest body) {
    return created(service.exportBenefitsFeed(body == null ? null : body.getEffectiveDate()));
  }

  @PostMapping(path = "/api/integration/time-attendance/import", consumes = "multipart/form-data")
  @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
  public ResponseEntity<TimeAttendanceImportResult> importTimeAttendance(
      @RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "true") boolean hasHeader) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String name =
        file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
            ? "upload.csv"
            : file.getOriginalFilename();
    TimeAttendanceImportResult r =
        service.importTimeAttendance(bytes, file.getContentType(), name, hasHeader);
    return ResponseEntity.created(URI.create("/api/integration/files/" + r.file().fileId()))
        .body(r);
  }

  @GetMapping("/api/integration/files")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public IntegrationFilePage listFiles(@Valid @ModelAttribute IntegrationFileListQuery q) {
    return service.list(q);
  }

  @GetMapping("/api/integration/files/{fileId}")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public IntegrationFile getFile(@PathVariable UUID fileId) {
    return service.get(fileId);
  }

  @GetMapping("/api/integration/files/{fileId}/content")
  @PreAuthorize(
      "hasAuthority('ADMIN:VIEW') and @integrationAccess.canDownload(#fileId, authentication)")
  public ResponseEntity<byte[]> downloadFile(@PathVariable UUID fileId) {
    IntegrationFile f = service.get(fileId);
    byte[] bytes;
    try {
      bytes = service.content(f);
    } catch (UncheckedIOException e) {
      throw new HrmsException(ErrorCode.FILE_NOT_FOUND);
    }
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(f.fileName()).build().toString())
        .eTag("\"" + f.sha256() + "\"")
        .contentType(MediaType.parseMediaType(service.contentType(fileId)))
        .body(bytes);
  }

  @GetMapping("/api/integration/status")
  @PreAuthorize("hasAuthority('ADMIN:VIEW')")
  public List<IntegrationStatus> status() {
    return service.status();
  }

  private static ResponseEntity<IntegrationFile> created(IntegrationFile f) {
    return ResponseEntity.created(URI.create("/api/integration/files/" + f.fileId())).body(f);
  }
}
