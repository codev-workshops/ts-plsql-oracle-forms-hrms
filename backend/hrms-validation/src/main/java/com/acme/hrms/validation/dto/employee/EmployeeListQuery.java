package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Query of GET /api/employees (Enter-Query replacement, COMPONENT_MAPPING.md §3.1; parameter names
 * reserved by the P0 contract). No client-supplied identity is involved.
 */
public class EmployeeListQuery {

  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(trim = true)
  private String lastName;

  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(trim = true)
  private String firstName;

  @Size(max = 100)
  @FieldMeta(trim = true)
  private String q;

  @Min(1)
  private Integer deptId;

  @Min(1)
  private Integer jobId;

  @Min(1)
  private Integer managerEmpId;

  @AllowedValues({"ACTIVE", "ON_LEAVE", "SUSPENDED", "TERMINATED"})
  private String status;

  @Pattern(regexp = EmployeeRules.LOCATION_CODE_PATTERN)
  private String locationCode;

  private LocalDate hireDateFrom;

  @FieldMeta(
      formatMessage = "hireDateFrom must be before or equal to hireDateTo",
      ruleId = "employee.hireDateRange",
      ruleValue = "hireDateFrom",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "hireDateFrom must be before or equal to hireDateTo")
  private LocalDate hireDateTo;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(100)
  private Integer size;

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = EmployeeRules.blankToNull(lastName);
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = EmployeeRules.blankToNull(firstName);
  }

  public String getQ() {
    return q;
  }

  public void setQ(String q) {
    this.q = EmployeeRules.blankToNull(q);
  }

  public Integer getDeptId() {
    return deptId;
  }

  public void setDeptId(Integer deptId) {
    this.deptId = deptId;
  }

  public Integer getJobId() {
    return jobId;
  }

  public void setJobId(Integer jobId) {
    this.jobId = jobId;
  }

  public Integer getManagerEmpId() {
    return managerEmpId;
  }

  public void setManagerEmpId(Integer managerEmpId) {
    this.managerEmpId = managerEmpId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = locationCode;
  }

  public LocalDate getHireDateFrom() {
    return hireDateFrom;
  }

  public void setHireDateFrom(LocalDate hireDateFrom) {
    this.hireDateFrom = hireDateFrom;
  }

  public LocalDate getHireDateTo() {
    return hireDateTo;
  }

  public void setHireDateTo(LocalDate hireDateTo) {
    this.hireDateTo = hireDateTo;
  }

  public Integer getPage() {
    return page;
  }

  public void setPage(Integer page) {
    this.page = page;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size;
  }

  @AssertTrue(message = "hireDateFrom must be before or equal to hireDateTo")
  public boolean isHireDateRangeValid() {
    return hireDateFrom == null || hireDateTo == null || !hireDateFrom.isAfter(hireDateTo);
  }
}
