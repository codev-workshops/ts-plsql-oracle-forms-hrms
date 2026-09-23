package com.acme.hrms.integration;

import com.acme.hrms.integration.IntegrationDtos.Artefact;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFile;
import com.acme.hrms.integration.IntegrationDtos.IntegrationFilePage;
import com.acme.hrms.integration.IntegrationDtos.IntegrationStatus;
import com.acme.hrms.integration.IntegrationDtos.PageMeta;
import com.acme.hrms.validation.dto.integration.IntegrationFileListQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Owner of {@code integration_files} / {@code integration_log} (ARCH-01). */
@Repository
public class IntegrationFileRepository {
  static final List<String> FEEDS = List.of("GL_JOURNAL", "BENEFITS_FEED", "TIME_ATTENDANCE");
  private static final DateTimeFormatter KEY_YEAR = DateTimeFormatter.ofPattern("yyyy");
  private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("MM");

  private static final RowMapper<IntegrationFile> FILE =
      (rs, i) -> {
        UUID id = rs.getObject("file_id", UUID.class);
        return new IntegrationFile(
            id,
            rs.getString("feed"),
            rs.getString("file_name"),
            rs.getString("status"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getInt("record_count"),
            rs.getString("source_ref"),
            rs.getString("storage_key"),
            "/api/integration/files/" + id + "/content",
            rs.getString("created_by"),
            rs.getObject("created_date", LocalDateTime.class),
            rs.getString("message"));
      };

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectStorage storage;
  private final Clock clock;

  public IntegrationFileRepository(
      NamedParameterJdbcTemplate jdbc, ObjectStorage storage, Clock clock) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.clock = clock;
  }

  /** Stores the bytes, then the metadata + log row in one transaction. */
  @Transactional
  public IntegrationFile store(Artefact a, String user) {
    UUID id = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now(clock);
    String key =
        a.feed()
            + "/"
            + now.format(KEY_YEAR)
            + "/"
            + now.format(KEY_MONTH)
            + "/"
            + id
            + "-"
            + a.fileName();
    storage.put(ObjectStorage.BUCKET, key, a.bytes());
    Map<String, Object> p = new HashMap<>();
    p.put("id", id);
    p.put("feed", a.feed());
    p.put("name", a.fileName());
    p.put("status", a.status());
    p.put("size", (long) a.bytes().length);
    p.put("sha", sha256(a.bytes()));
    p.put("count", a.recordCount());
    p.put("ref", a.sourceRef());
    p.put("bucket", ObjectStorage.BUCKET);
    p.put("key", key);
    p.put("ct", a.contentType());
    p.put("msg", a.message());
    p.put("user", user);
    p.put("now", now);
    jdbc.update(
        """
        insert into integration_files (file_id, feed, file_name, status, size_bytes, sha256,
          record_count, source_ref, storage_bucket, storage_key, content_type, message,
          created_by, created_date)
        values (:id, :feed, :name, :status, :size, :sha, :count, :ref, :bucket, :key, :ct,
          :msg, :user, :now)
        """,
        p);
    log(a.feed(), a.status(), id, a.sourceRef(), a.recordCount(), a.message(), user, now);
    return get(id).orElseThrow();
  }

  @Transactional
  public void logFailure(String feed, @Nullable String sourceRef, String message, String user) {
    log(feed, "FAILED", null, sourceRef, null, message, user, LocalDateTime.now(clock));
  }

  private void log(
      String feed,
      String status,
      @Nullable UUID fileId,
      @Nullable String sourceRef,
      @Nullable Integer count,
      @Nullable String message,
      String user,
      LocalDateTime now) {
    Map<String, Object> p = new HashMap<>();
    p.put("feed", feed);
    p.put("status", status);
    p.put("file", fileId);
    p.put("ref", sourceRef);
    p.put("count", count);
    p.put("msg", message);
    p.put("user", user);
    p.put("now", now);
    jdbc.update(
        """
        insert into integration_log (log_id, feed, status, file_id, source_ref, record_count,
          message, created_by, created_date)
        values (nextval('seq_integration_log'), :feed, :status, :file, :ref, :count, :msg,
          :user, :now)
        """,
        p);
  }

  public Optional<IntegrationFile> get(UUID id) {
    List<IntegrationFile> rows =
        jdbc.query("select * from integration_files where file_id = :id", Map.of("id", id), FILE);
    return rows.stream().findFirst();
  }

  public String contentType(UUID id) {
    return jdbc.queryForObject(
        "select content_type from integration_files where file_id = :id",
        Map.of("id", id),
        String.class);
  }

  public byte[] content(IntegrationFile f) {
    return storage.get(ObjectStorage.BUCKET, f.storageKey());
  }

  public IntegrationFilePage list(IntegrationFileListQuery q) {
    int page = q.getPage() == null ? 0 : q.getPage();
    int size = q.getSize() == null ? 50 : q.getSize();
    StringBuilder where = new StringBuilder(" where 1=1");
    Map<String, Object> p = new HashMap<>();
    if (q.getFeed() != null) {
      where.append(" and feed = :feed");
      p.put("feed", q.getFeed());
    }
    if (q.getStatus() != null) {
      where.append(" and status = :status");
      p.put("status", q.getStatus());
    }
    if (q.getFrom() != null) {
      where.append(" and created_date >= :from");
      p.put("from", q.getFrom().atStartOfDay());
    }
    if (q.getTo() != null) {
      where.append(" and created_date < :to");
      p.put("to", q.getTo().plusDays(1).atStartOfDay());
    }
    Long total =
        jdbc.queryForObject("select count(*) from integration_files" + where, p, Long.class);
    p.put("limit", size);
    p.put("offset", (long) page * size);
    List<IntegrationFile> rows =
        jdbc.query(
            "select * from integration_files"
                + where
                + " order by created_date desc, file_id desc limit :limit offset :offset",
            p,
            FILE);
    return new IntegrationFilePage(rows, PageMeta.of(page, size, total == null ? 0 : total));
  }

  /** Latest {@code integration_log} row per feed; {@code NEVER_RUN} when none. */
  public List<IntegrationStatus> status() {
    Map<String, IntegrationStatus> latest = new HashMap<>();
    jdbc.query(
        """
        select distinct on (feed) feed, status, created_date, file_id, created_by, message
          from integration_log
         order by feed, created_date desc, log_id desc
        """,
        rs -> {
          latest.put(
              rs.getString("feed"),
              new IntegrationStatus(
                  rs.getString("feed"),
                  rs.getString("status"),
                  rs.getObject("created_date", LocalDateTime.class),
                  rs.getObject("file_id", UUID.class),
                  rs.getString("created_by"),
                  rs.getString("message")));
        });
    List<IntegrationStatus> out = new ArrayList<>();
    for (String feed : FEEDS) {
      out.add(
          latest.getOrDefault(
              feed, new IntegrationStatus(feed, "NEVER_RUN", null, null, null, null)));
    }
    return out;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
