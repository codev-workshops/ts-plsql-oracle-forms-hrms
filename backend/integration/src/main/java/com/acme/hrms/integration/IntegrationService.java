package com.acme.hrms.integration;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFile;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFilePage;
import com.acme.hrms.integration.IntegrationDtos.IntegrationStatus;
import com.acme.hrms.integration.IntegrationDtos.TimeAttendanceImportResult;
import com.acme.hrms.validation.dto.integration.IntegrationFileListQuery;
import com.acme.hrms.validation.dto.integration.IntegrationRules;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {
  private final GlFeedWriter gl;
  private final BenefitsFeedWriter benefits;
  private final TimeAttendanceImporter importer;
  private final IntegrationFileRepository files;

  public IntegrationService(
      GlFeedWriter gl,
      BenefitsFeedWriter benefits,
      TimeAttendanceImporter importer,
      IntegrationFileRepository files) {
    this.gl = gl;
    this.benefits = benefits;
    this.importer = importer;
    this.files = files;
  }

  public IntegrationFile generateGlFeed(long runId) {
    String user = CurrentCaller.require().userId();
    try {
      return files.store(gl.write(runId), user);
    } catch (HrmsException e) {
      if (e.code() == ErrorCode.RUN_NOT_EXPORTABLE) {
        files.logFailure("GL_JOURNAL", Long.toString(runId), e.getMessage(), user);
      }
      throw e;
    }
  }

  public IntegrationFile exportBenefitsFeed(@Nullable LocalDate effectiveDate) {
    return files.store(benefits.write(effectiveDate), CurrentCaller.require().userId());
  }

  public TimeAttendanceImportResult importTimeAttendance(
      byte[] content, @Nullable String contentType, String originalName, boolean hasHeader) {
    if (content.length > IntegrationRules.UPLOAD_MAX_BYTES) {
      throw new HrmsException(ErrorCode.PAYLOAD_TOO_LARGE, "File exceeds 5 MiB", "file");
    }
    if (contentType == null || !contentType.toLowerCase().startsWith("text/csv")) {
      throw new HrmsException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Expected text/csv", "file");
    }
    String user = CurrentCaller.require().userId();
    TimeAttendanceImporter.Outcome out;
    try {
      out = importer.parse(content, hasHeader, originalName);
    } catch (HrmsException e) {
      if (e.code() == ErrorCode.IMPORT_REJECTED) {
        files.logFailure("TIME_ATTENDANCE", originalName, e.getMessage(), user);
      }
      throw e;
    }
    IntegrationFile stored = files.store(out.artefact(), user);
    return new TimeAttendanceImportResult(
        stored,
        out.accepted(),
        out.lines().size() - out.accepted(),
        out.lines(),
        false,
        null,
        null);
  }

  public IntegrationFilePage list(IntegrationFileListQuery q) {
    return files.list(q);
  }

  public IntegrationFile get(UUID fileId) {
    return files.get(fileId).orElseThrow(() -> new HrmsException(ErrorCode.FILE_NOT_FOUND));
  }

  public byte[] content(IntegrationFile f) {
    return files.content(f);
  }

  public String contentType(UUID fileId) {
    return files.contentType(fileId);
  }

  public List<IntegrationStatus> status() {
    return files.status();
  }
}
