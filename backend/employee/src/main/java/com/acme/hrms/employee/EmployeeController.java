package com.acme.hrms.employee;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.employee.EmployeeDtos.Dependent;
import com.acme.hrms.employee.EmployeeDtos.EmergencyContact;
import com.acme.hrms.employee.EmployeeDtos.EmployeeDetail;
import com.acme.hrms.employee.EmployeeDtos.EmployeeHistoryEntry;
import com.acme.hrms.employee.EmployeeDtos.EmployeeListItem;
import com.acme.hrms.employee.EmployeeDtos.EmployeeSummary;
import com.acme.hrms.employee.EmployeeDtos.Page;
import com.acme.hrms.employee.EmployeeRepository.SearchFilter;
import com.acme.hrms.validation.dto.employee.DependentRequest;
import com.acme.hrms.validation.dto.employee.EmergencyContactRequest;
import com.acme.hrms.validation.dto.employee.EmployeeCreateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeListQuery;
import com.acme.hrms.validation.dto.employee.EmployeeTerminateRequest;
import com.acme.hrms.validation.dto.employee.EmployeeTransferRequest;
import com.acme.hrms.validation.dto.employee.EmployeeUpdateRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every {@code /api/employees} path of contracts/p3-employee/openapi.yaml except the three
 * salary-module paths (SalaryController). There is deliberately no {@code DELETE} mapping: the
 * GlobalExceptionHandler turns it into {@code 405 -20504}.
 */
@RestController
public class EmployeeController {

  static final String SUMMARY_FIELDS = "id,name,jobTitle";
  static final int DEFAULT_SIZE = 20;

  private final EmployeeService service;
  private final EmployeeRepository repository;
  private final EmployeeAccess access;

  public EmployeeController(
      EmployeeService service, EmployeeRepository repository, EmployeeAccess access) {
    this.service = service;
    this.repository = repository;
    this.access = access;
  }

  @GetMapping("/api/employees")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public Object list(
      EmployeeListQuery query,
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false) @Nullable String fields,
      @RequestParam(required = false, defaultValue = "false") boolean excludeSelf) {
    CallerIdentity caller = CurrentCaller.require();
    access.validateList(query);
    if (fields != null && !SUMMARY_FIELDS.equals(fields)) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED, "Only fields=" + SUMMARY_FIELDS + " is accepted", "fields");
    }
    int page = query.getPage() == null ? 0 : query.getPage();
    int size = query.getSize() == null ? DEFAULT_SIZE : query.getSize();
    SearchFilter filter =
        new SearchFilter(
            query.getLastName(),
            query.getFirstName(),
            query.getQ(),
            toLong(query.getDeptId()),
            toLong(query.getJobId()),
            toLong(query.getManagerEmpId()),
            query.getStatus(),
            active,
            query.getLocationCode(),
            query.getHireDateFrom(),
            query.getHireDateTo(),
            excludeSelf ? caller.empId() : null);
    if (fields != null) {
      Page<EmployeeSummary> summaries = repository.searchSummaries(filter, page, size);
      return summaries;
    }
    Page<EmployeeListItem> items =
        repository.search(filter, page, size).mapContent(EmployeeRow::toListItem);
    return items;
  }

  @PostMapping("/api/employees")
  @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
  public ResponseEntity<EmployeeDetail> create(
      @RequestBody(required = false) @Nullable EmployeeCreateRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    EmployeeDetail created = service.create(access.validateCreate(body), caller);
    return ResponseEntity.created(URI.create("/api/employees/" + created.id()))
        .eTag(etag(created))
        .body(created);
  }

  @GetMapping("/api/employees/{id}")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public ResponseEntity<EmployeeDetail> get(@PathVariable long id) {
    EmployeeDetail detail = service.get(id, CurrentCaller.require());
    return ResponseEntity.ok().eTag(etag(detail)).body(detail);
  }

  @PutMapping("/api/employees/{id}")
  @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
  public ResponseEntity<EmployeeDetail> update(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
      @RequestBody(required = false) @Nullable EmployeeUpdateRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    int version = parseIfMatch(ifMatch);
    EmployeeDetail updated = service.update(id, version, access.validateUpdate(body), caller);
    return ResponseEntity.ok().eTag(etag(updated)).body(updated);
  }

  @PostMapping("/api/employees/{id}/terminate")
  @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
  public EmployeeDetail terminate(
      @PathVariable long id,
      @RequestBody(required = false) @Nullable EmployeeTerminateRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    return service.terminate(id, access.validate(body), caller);
  }

  @PostMapping("/api/employees/{id}/transfer")
  @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
  public EmployeeDetail transfer(
      @PathVariable long id,
      @RequestBody(required = false) @Nullable EmployeeTransferRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    return service.transfer(id, access.validate(body), caller);
  }

  @GetMapping("/api/employees/{id}/history")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public List<EmployeeHistoryEntry> history(@PathVariable long id) {
    return service.history(id, CurrentCaller.require());
  }

  @GetMapping("/api/employees/{id}/dependents")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public List<Dependent> dependents(@PathVariable long id) {
    return service.dependents(id, CurrentCaller.require());
  }

  @PostMapping("/api/employees/{id}/dependents")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public ResponseEntity<Dependent> addDependent(
      @PathVariable long id, @RequestBody(required = false) @Nullable DependentRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    Dependent created = service.addDependent(id, access.validate(body), caller);
    return ResponseEntity.created(
            URI.create("/api/employees/" + id + "/dependents/" + created.dependentId()))
        .body(created);
  }

  @PutMapping("/api/employees/{id}/dependents/{dependentId}")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public Dependent updateDependent(
      @PathVariable long id,
      @PathVariable long dependentId,
      @RequestBody(required = false) @Nullable DependentRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    return service.updateDependent(id, dependentId, access.validate(body), caller);
  }

  @GetMapping("/api/employees/{id}/contacts")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public List<EmergencyContact> contacts(@PathVariable long id) {
    return service.contacts(id, CurrentCaller.require());
  }

  @PostMapping("/api/employees/{id}/contacts")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public ResponseEntity<EmergencyContact> addContact(
      @PathVariable long id,
      @RequestBody(required = false) @Nullable EmergencyContactRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    EmergencyContact created = service.addContact(id, access.validate(body), caller);
    return ResponseEntity.created(
            URI.create("/api/employees/" + id + "/contacts/" + created.contactId()))
        .body(created);
  }

  @PutMapping("/api/employees/{id}/contacts/{contactId}")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public EmergencyContact updateContact(
      @PathVariable long id,
      @PathVariable long contactId,
      @RequestBody(required = false) @Nullable EmergencyContactRequest body) {
    CallerIdentity caller = CurrentCaller.require();
    access.requireWritable();
    return service.updateContact(id, contactId, access.validate(body), caller);
  }

  static String etag(EmployeeDetail d) {
    return "\"" + d.version() + "\"";
  }

  /** {@code If-Match: "3"} (or {@code 3}) → 3; missing → {@code 428 PRECONDITION_REQUIRED}. */
  static int parseIfMatch(@Nullable String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new HrmsException(ErrorCode.PRECONDITION_REQUIRED);
    }
    String v = ifMatch.trim();
    if (v.startsWith("W/")) {
      v = v.substring(2);
    }
    v = v.replace("\"", "");
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new HrmsException(
          ErrorCode.VALIDATION_FAILED, "If-Match must carry the employee version", "If-Match");
    }
  }

  @Nullable
  private static Long toLong(@Nullable Integer v) {
    return v == null ? null : v.longValue();
  }
}
