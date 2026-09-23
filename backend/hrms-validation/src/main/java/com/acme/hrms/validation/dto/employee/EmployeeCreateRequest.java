package com.acme.hrms.validation.dto.employee;

import com.acme.hrms.validation.constraints.HireDateWithinLimit;
import com.acme.hrms.validation.constraints.Ssn;
import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of POST /api/employees (contracts/p3-employee/openapi.yaml). {@code empNumber} is never
 * accepted: it is assigned from SEQ_EMP_NUMBER. Initial salary travels with the hire and is written
 * by salary-module in the same transaction.
 */
public class EmployeeCreateRequest extends StrictRequest {

  @NotBlank
  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(
      trim = true,
      requiredMessage = "First name is required",
      maxLengthMessage = "First name may not exceed 50 characters")
  private String firstName;

  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(trim = true)
  private String middleName;

  @NotBlank
  @Size(max = EmployeeRules.NAME_MAX)
  @FieldMeta(
      trim = true,
      requiredMessage = "Last name is required",
      maxLengthMessage = "Last name may not exceed 50 characters")
  private String lastName;

  @PastOrPresent
  @FieldMeta(
      formatMessage = "Date of birth cannot be in the future",
      ruleId = "employee.dateNotFuture",
      ruleValue = "today",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "Date of birth cannot be in the future")
  private LocalDate dateOfBirth;

  @AllowedValues({"M", "F", "O"})
  private String gender;

  @Size(max = EmployeeRules.MARITAL_STATUS_MAX)
  @FieldMeta(trim = true)
  private String maritalStatus;

  @Size(max = EmployeeRules.NATIONALITY_MAX)
  @FieldMeta(trim = true)
  private String nationality;

  @Ssn
  @Size(max = 11)
  @FieldMeta(
      trim = true,
      patternMessage = "SSN must be 9 digits (NNN-NN-NNNN)",
      ruleId = "employee.ssn",
      ruleErrorCode = "VALIDATION_FAILED",
      ruleMessage = "SSN must be 9 digits (NNN-NN-NNNN)",
      sensitive = true)
  private String ssn;

  @Email
  @Size(max = EmployeeRules.EMAIL_MAX)
  @FieldMeta(trim = true, formatMessage = "Enter a valid e-mail address")
  private String email;

  @Pattern(regexp = EmployeeRules.PHONE_PATTERN, message = EmployeeRules.PHONE_MESSAGE)
  @Size(max = EmployeeRules.PHONE_MAX)
  @FieldMeta(trim = true, patternMessage = EmployeeRules.PHONE_MESSAGE)
  private String phoneWork;

  @Pattern(regexp = EmployeeRules.PHONE_PATTERN, message = EmployeeRules.PHONE_MESSAGE)
  @Size(max = EmployeeRules.PHONE_MAX)
  @FieldMeta(trim = true, patternMessage = EmployeeRules.PHONE_MESSAGE)
  private String phoneMobile;

  @Size(max = EmployeeRules.ADDRESS_MAX)
  @FieldMeta(trim = true)
  private String addressLine1;

  @Size(max = EmployeeRules.ADDRESS_MAX)
  @FieldMeta(trim = true)
  private String addressLine2;

  @Size(max = EmployeeRules.CITY_MAX)
  @FieldMeta(trim = true)
  private String city;

  @Size(max = EmployeeRules.CITY_MAX)
  @FieldMeta(trim = true)
  private String stateProvince;

  @Size(max = EmployeeRules.POSTAL_MAX)
  @FieldMeta(trim = true)
  private String postalCode;

  @Pattern(regexp = EmployeeRules.COUNTRY_CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = "Country code must be 2-3 upper-case letters")
  private String countryCode;

  @NotNull
  @HireDateWithinLimit
  @FieldMeta(
      requiredMessage = "Hire date is required",
      ruleId = "employee.hireDateLimit",
      ruleErrorCode = "-20501",
      ruleMessage = "Hire date cannot be more than 90 days in the future")
  private LocalDate hireDate;

  @NotNull
  @Min(1)
  @FieldMeta(
      requiredMessage = "Department is required",
      formatMessage = "Select an active department")
  private Integer deptId;

  @NotNull
  @Min(1)
  @FieldMeta(
      requiredMessage = "Job title is required",
      formatMessage = "Select an active job title")
  private Integer jobId;

  @Min(1)
  @FieldMeta(formatMessage = "Select an active manager (not the employee themselves)")
  private Integer managerEmpId;

  @Pattern(regexp = EmployeeRules.LOCATION_CODE_PATTERN)
  @FieldMeta(trim = true, patternMessage = "Select a location")
  private String locationCode;

  @AllowedValues({"FULL_TIME", "PART_TIME", "CONTRACT", "INTERN"})
  private String employmentType;

  @DecimalMin(value = "0.01")
  @Digits(integer = 10, fraction = 2)
  @FieldMeta(
      ruleId = "salary.positive",
      ruleErrorCode = "-20101",
      ruleMessage = "Salary must be positive")
  private BigDecimal initialSalary;

  @Size(max = EmployeeRules.NOTES_MAX)
  @FieldMeta(trim = true)
  private String notes;

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = EmployeeRules.blankToNull(firstName);
  }

  public String getMiddleName() {
    return middleName;
  }

  public void setMiddleName(String middleName) {
    this.middleName = EmployeeRules.blankToNull(middleName);
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = EmployeeRules.blankToNull(lastName);
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  public String getGender() {
    return gender;
  }

  public void setGender(String gender) {
    this.gender = gender;
  }

  public String getMaritalStatus() {
    return maritalStatus;
  }

  public void setMaritalStatus(String maritalStatus) {
    this.maritalStatus = EmployeeRules.blankToNull(maritalStatus);
  }

  public String getNationality() {
    return nationality;
  }

  public void setNationality(String nationality) {
    this.nationality = EmployeeRules.blankToNull(nationality);
  }

  public String getSsn() {
    return ssn;
  }

  public void setSsn(String ssn) {
    this.ssn = EmployeeRules.blankToNull(ssn);
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = EmployeeRules.blankToNull(email);
  }

  public String getPhoneWork() {
    return phoneWork;
  }

  public void setPhoneWork(String phoneWork) {
    this.phoneWork = EmployeeRules.blankToNull(phoneWork);
  }

  public String getPhoneMobile() {
    return phoneMobile;
  }

  public void setPhoneMobile(String phoneMobile) {
    this.phoneMobile = EmployeeRules.blankToNull(phoneMobile);
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public void setAddressLine1(String addressLine1) {
    this.addressLine1 = EmployeeRules.blankToNull(addressLine1);
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public void setAddressLine2(String addressLine2) {
    this.addressLine2 = EmployeeRules.blankToNull(addressLine2);
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = EmployeeRules.blankToNull(city);
  }

  public String getStateProvince() {
    return stateProvince;
  }

  public void setStateProvince(String stateProvince) {
    this.stateProvince = EmployeeRules.blankToNull(stateProvince);
  }

  public String getPostalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = EmployeeRules.blankToNull(postalCode);
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = EmployeeRules.blankToNull(countryCode);
  }

  public LocalDate getHireDate() {
    return hireDate;
  }

  public void setHireDate(LocalDate hireDate) {
    this.hireDate = hireDate;
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

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = EmployeeRules.blankToNull(locationCode);
  }

  public String getEmploymentType() {
    return employmentType;
  }

  public void setEmploymentType(String employmentType) {
    this.employmentType = employmentType;
  }

  public BigDecimal getInitialSalary() {
    return initialSalary;
  }

  public void setInitialSalary(BigDecimal initialSalary) {
    this.initialSalary = initialSalary;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = EmployeeRules.blankToNull(notes);
  }
}
