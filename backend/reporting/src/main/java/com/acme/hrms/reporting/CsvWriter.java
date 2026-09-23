package com.acme.hrms.reporting;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;

/**
 * RFC 4180 rendering of a record list: header = record component names in declaration order (= JSON
 * property order), null = empty field, CRLF line ends, UTF-8 without BOM. Fields are quoted only
 * when they contain a comma, quote or line break. Money / Rate / Days are already formatted
 * strings, dates are ISO.
 */
public final class CsvWriter {

  public static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private CsvWriter() {}

  /** Content negotiation of the report routes: JSON, CSV or {@code 406 NOT_ACCEPTABLE}. */
  public static boolean wantsCsv(@Nullable String accept, boolean csvAlias) {
    if (csvAlias) {
      return true;
    }
    if (accept == null || accept.isBlank()) {
      return false;
    }
    List<MediaType> types = MediaType.parseMediaTypes(accept);
    MediaType.sortBySpecificityAndQuality(types);
    for (MediaType t : types) {
      if (t.getQualityValue() <= 0) {
        continue;
      }
      if (t.isCompatibleWith(MediaType.APPLICATION_JSON)) {
        return false;
      }
      if (t.isCompatibleWith(TEXT_CSV)) {
        return true;
      }
    }
    throw new HrmsException(ErrorCode.NOT_ACCEPTABLE);
  }

  public static <T extends Record> ResponseEntity<byte[]> respond(
      String reportName, LocalDate asOf, Class<T> type, List<T> rows, Set<String> exclude) {
    String body = render(type, rows, exclude);
    return ResponseEntity.ok()
        .contentType(TEXT_CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + reportName + "-" + asOf + ".csv\"")
        .body(body.getBytes(StandardCharsets.UTF_8));
  }

  public static <T extends Record> String render(Class<T> type, List<T> rows, Set<String> exclude) {
    RecordComponent[] all = type.getRecordComponents();
    List<RecordComponent> cols =
        java.util.Arrays.stream(all).filter(c -> !exclude.contains(c.getName())).toList();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(cols.get(i).getName());
    }
    sb.append("\r\n");
    for (T row : rows) {
      for (int i = 0; i < cols.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(field(value(cols.get(i), row)));
      }
      sb.append("\r\n");
    }
    return sb.toString();
  }

  @Nullable
  private static Object value(RecordComponent c, Object row) {
    try {
      return c.getAccessor().invoke(row);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(c.getName(), e);
    }
  }

  static String field(@Nullable Object v) {
    if (v == null) {
      return "";
    }
    String s = v.toString();
    if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
      return s;
    }
    return '"' + s.replace("\"", "\"\"") + '"';
  }
}
