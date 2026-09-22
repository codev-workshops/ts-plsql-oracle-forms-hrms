package com.acme.hrms.salary;

import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.salary.SalaryDtos.SalaryRecord;
import com.acme.hrms.validation.dto.employee.SalaryChangeRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The three salary-module operations frozen in contracts/p3-employee/openapi.yaml. */
@RestController
public class SalaryController {

  private final SalaryService service;

  public SalaryController(SalaryService service) {
    this.service = service;
  }

  @GetMapping("/api/employees/{id}/salary")
  @PreAuthorize("isAuthenticated()")
  public SalaryRecord current(@PathVariable long id) {
    return service.current(id, CurrentCaller.require());
  }

  @PostMapping("/api/employees/{id}/salary")
  @PreAuthorize("hasAuthority('PAYROLL:EDIT')")
  public ResponseEntity<SalaryRecord> change(
      @PathVariable long id, @RequestBody SalaryChangeRequest body) {
    var caller = CurrentCaller.require();
    SalaryRecord created = service.change(id, body, caller.userId());
    return ResponseEntity.created(URI.create("/api/employees/" + id + "/salary")).body(created);
  }

  @GetMapping("/api/employees/{id}/salary/history")
  @PreAuthorize("isAuthenticated()")
  public List<SalaryRecord> history(@PathVariable long id) {
    return service.history(id, CurrentCaller.require());
  }
}
