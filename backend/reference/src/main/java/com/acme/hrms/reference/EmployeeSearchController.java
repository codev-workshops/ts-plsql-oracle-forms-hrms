package com.acme.hrms.reference;

import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.reference.ReferenceDtos.PageOfEmployeeSummary;
import com.acme.hrms.validation.dto.EmployeeSearchQuery;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/employees - the identity used for {@code excludeSelf} is the JWT's, never a param. */
@RestController
@Validated
public class EmployeeSearchController {

  static final int DEFAULT_SIZE = 20;

  private final EmployeeSearchRepository repository;

  public EmployeeSearchController(EmployeeSearchRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/employees")
  @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
  public PageOfEmployeeSummary searchEmployees(@Valid EmployeeSearchQuery query) {
    CallerIdentity caller = CurrentCaller.require();
    boolean excludeSelf = Boolean.TRUE.equals(query.getExcludeSelf());
    int page = query.getPage() == null ? 0 : query.getPage();
    int size = query.getSize() == null ? DEFAULT_SIZE : query.getSize();
    return repository.searchActive(query.getQ(), excludeSelf ? caller.empId() : null, page, size);
  }
}
