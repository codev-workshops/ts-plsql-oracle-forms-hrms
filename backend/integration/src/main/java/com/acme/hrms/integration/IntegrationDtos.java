package com.acme.hrms.integration;

import com.acme.hrms.common.error.ApiError;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Response shapes of the frozen P5 integration contract (declaration order = JSON order). */
public final class IntegrationDtos {
  private IntegrationDtos() {}

  public record PageMeta(int page, int size, long totalElements, int totalPages) {
    public static PageMeta of(int page, int size, long total) {
      return new PageMeta(page, size, total, (int) Math.ceil(total / (double) size));
    }
  }

  public record IntegrationFile(
      UUID fileId,
      String feed,
      String fileName,
      String status,
      long sizeBytes,
      String sha256,
      int recordCount,
      @Nullable String sourceRef,
      String storageKey,
      String contentUrl,
      String createdBy,
      LocalDateTime createdAt,
      @Nullable String message) {}

  public record IntegrationFilePage(List<IntegrationFile> content, PageMeta page) {}

  public record TimeAttendanceLineResult(
      int line,
      String empNumber,
      @Nullable Long empId,
      String workDate,
      String hoursRegular,
      String hoursOvertime,
      @Nullable Boolean overtimeEligible,
      String verdict,
      @Nullable ApiError error) {}

  public record TimeAttendanceImportResult(
      IntegrationFile file,
      int accepted,
      int rejected,
      List<TimeAttendanceLineResult> lines,
      boolean applied,
      @Nullable Object targetTable,
      @Nullable Object payElementMapping) {}

  public record IntegrationStatus(
      String feed,
      String status,
      @Nullable LocalDateTime lastRunAt,
      @Nullable UUID lastFileId,
      @Nullable String lastRunBy,
      @Nullable String message) {}

  /** In-memory artefact before it is persisted. */
  public record Artefact(
      String feed,
      String fileName,
      String status,
      byte[] bytes,
      int recordCount,
      @Nullable String sourceRef,
      String contentType,
      @Nullable String message) {}
}
