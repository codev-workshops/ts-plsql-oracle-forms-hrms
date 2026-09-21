package com.acme.hrms.reference;

import com.acme.hrms.reference.ReferenceDtos.DepartmentRef;
import com.acme.hrms.reference.ReferenceDtos.JobTitleRef;
import com.acme.hrms.reference.ReferenceDtos.LeaveTypeRef;
import com.acme.hrms.reference.ReferenceDtos.LocationRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** /api/reference/* - sorted lists with {@code Cache-Control: private, max-age=300} and ETag. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

  static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

  private final ReferenceRepository repository;
  private final ObjectMapper mapper;

  public ReferenceController(ReferenceRepository repository, ObjectMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @GetMapping("/departments")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<DepartmentRef>> listDepartments(
      @RequestParam(name = "active", defaultValue = "true") boolean active, WebRequest request) {
    return cached(repository.departments(active), request);
  }

  @GetMapping("/job-titles")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<JobTitleRef>> listJobTitles(
      @RequestParam(name = "active", defaultValue = "true") boolean active, WebRequest request) {
    return cached(repository.jobTitles(active), request);
  }

  @GetMapping("/locations")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<LocationRef>> listLocations(
      @RequestParam(name = "active", defaultValue = "true") boolean active, WebRequest request) {
    return cached(repository.locations(active), request);
  }

  @GetMapping("/leave-types")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<List<LeaveTypeRef>> listLeaveTypes(
      @RequestParam(name = "active", defaultValue = "true") boolean active, WebRequest request) {
    return cached(repository.leaveTypes(active), request);
  }

  private <T> ResponseEntity<List<T>> cached(List<T> body, WebRequest request) {
    String etag = etag(body);
    if (request.checkNotModified(etag)) {
      return ResponseEntity.status(304).cacheControl(CACHE).eTag(etag).build();
    }
    return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(body);
  }

  String etag(Object body) {
    try {
      byte[] json = mapper.writeValueAsBytes(body);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
      return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
