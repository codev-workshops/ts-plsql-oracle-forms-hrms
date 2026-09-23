package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.Department;
import com.acme.hrms.admin.AdminDtos.JobGrade;
import com.acme.hrms.admin.AdminDtos.JobTitle;
import com.acme.hrms.admin.AdminDtos.LeaveType;
import com.acme.hrms.admin.AdminDtos.Location;
import com.acme.hrms.admin.AdminDtos.SystemParameter;
import com.acme.hrms.validation.dto.admin.DepartmentRequest;
import com.acme.hrms.validation.dto.admin.JobGradeRequest;
import com.acme.hrms.validation.dto.admin.JobTitleRequest;
import com.acme.hrms.validation.dto.admin.LeaveTypeRequest;
import com.acme.hrms.validation.dto.admin.LocationRequest;
import com.acme.hrms.validation.dto.admin.SystemParameterRequest;
import com.acme.hrms.validation.dto.admin.SystemParameterUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/admin/*} reference-data routes of the frozen P5 contract. */
@RestController
@Validated
public class AdminReferenceController {
  private static final String VIEW = "hasAuthority('ADMIN:VIEW')";
  private static final String EDIT = "hasAuthority('ADMIN:EDIT')";

  private final DepartmentAdminService departments;
  private final JobGradeAdminService grades;
  private final JobTitleAdminService titles;
  private final LocationAdminService locations;
  private final LeaveTypeAdminService leaveTypes;
  private final SystemParameterAdminService parameters;

  public AdminReferenceController(
      DepartmentAdminService departments,
      JobGradeAdminService grades,
      JobTitleAdminService titles,
      LocationAdminService locations,
      LeaveTypeAdminService leaveTypes,
      SystemParameterAdminService parameters) {
    this.departments = departments;
    this.grades = grades;
    this.titles = titles;
    this.locations = locations;
    this.leaveTypes = leaveTypes;
    this.parameters = parameters;
  }

  // --- departments -----------------------------------------------------------

  @GetMapping("/api/admin/departments")
  @PreAuthorize(VIEW)
  public List<Department> listDepartments(
      @RequestParam(required = false) @Nullable Boolean active) {
    return departments.list(active);
  }

  @PostMapping("/api/admin/departments")
  @PreAuthorize(EDIT)
  public ResponseEntity<Department> createDepartment(@Valid @RequestBody DepartmentRequest body) {
    Department d = departments.create(body);
    return ResponseEntity.created(URI.create("/api/admin/departments/" + d.deptId())).body(d);
  }

  @GetMapping("/api/admin/departments/{deptId}")
  @PreAuthorize(VIEW)
  public Department getDepartment(@PathVariable @Min(1) long deptId) {
    return departments.get(deptId);
  }

  @PutMapping("/api/admin/departments/{deptId}")
  @PreAuthorize(EDIT)
  public Department updateDepartment(
      @PathVariable @Min(1) long deptId, @Valid @RequestBody DepartmentRequest body) {
    return departments.update(deptId, body);
  }

  @DeleteMapping("/api/admin/departments/{deptId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateDepartment(@PathVariable @Min(1) long deptId) {
    departments.deactivate(deptId);
    return ResponseEntity.noContent().build();
  }

  // --- job grades --------------------------------------------------------------

  @GetMapping("/api/admin/job-grades")
  @PreAuthorize(VIEW)
  public List<JobGrade> listJobGrades(@RequestParam(required = false) @Nullable Boolean active) {
    return grades.list(active);
  }

  @PostMapping("/api/admin/job-grades")
  @PreAuthorize(EDIT)
  public ResponseEntity<JobGrade> createJobGrade(@Valid @RequestBody JobGradeRequest body) {
    JobGrade g = grades.create(body);
    return ResponseEntity.created(URI.create("/api/admin/job-grades/" + g.gradeId())).body(g);
  }

  @GetMapping("/api/admin/job-grades/{gradeId}")
  @PreAuthorize(VIEW)
  public JobGrade getJobGrade(@PathVariable @Min(1) int gradeId) {
    return grades.get(gradeId);
  }

  @PutMapping("/api/admin/job-grades/{gradeId}")
  @PreAuthorize(EDIT)
  public JobGrade updateJobGrade(
      @PathVariable @Min(1) int gradeId, @Valid @RequestBody JobGradeRequest body) {
    return grades.update(gradeId, body);
  }

  @DeleteMapping("/api/admin/job-grades/{gradeId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateJobGrade(@PathVariable @Min(1) int gradeId) {
    grades.deactivate(gradeId);
    return ResponseEntity.noContent().build();
  }

  // --- job titles --------------------------------------------------------------

  @GetMapping("/api/admin/job-titles")
  @PreAuthorize(VIEW)
  public List<JobTitle> listJobTitles(@RequestParam(required = false) @Nullable Boolean active) {
    return titles.list(active);
  }

  @PostMapping("/api/admin/job-titles")
  @PreAuthorize(EDIT)
  public ResponseEntity<JobTitle> createJobTitle(@Valid @RequestBody JobTitleRequest body) {
    JobTitle t = titles.create(body);
    return ResponseEntity.created(URI.create("/api/admin/job-titles/" + t.jobId())).body(t);
  }

  @GetMapping("/api/admin/job-titles/{jobId}")
  @PreAuthorize(VIEW)
  public JobTitle getJobTitle(@PathVariable @Min(1) long jobId) {
    return titles.get(jobId);
  }

  @PutMapping("/api/admin/job-titles/{jobId}")
  @PreAuthorize(EDIT)
  public JobTitle updateJobTitle(
      @PathVariable @Min(1) long jobId, @Valid @RequestBody JobTitleRequest body) {
    return titles.update(jobId, body);
  }

  @DeleteMapping("/api/admin/job-titles/{jobId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateJobTitle(@PathVariable @Min(1) long jobId) {
    titles.deactivate(jobId);
    return ResponseEntity.noContent().build();
  }

  // --- locations ---------------------------------------------------------------

  @GetMapping("/api/admin/locations")
  @PreAuthorize(VIEW)
  public List<Location> listLocations(@RequestParam(required = false) @Nullable Boolean active) {
    return locations.list(active);
  }

  @PostMapping("/api/admin/locations")
  @PreAuthorize(EDIT)
  public ResponseEntity<Location> createLocation(@Valid @RequestBody LocationRequest body) {
    Location l = locations.create(body);
    return ResponseEntity.created(URI.create("/api/admin/locations/" + l.locationCode())).body(l);
  }

  @GetMapping("/api/admin/locations/{locationCode}")
  @PreAuthorize(VIEW)
  public Location getLocation(
      @PathVariable @Size(min = 1, max = 10) @Pattern(regexp = "^[A-Z0-9_-]+$")
          String locationCode) {
    return locations.get(locationCode);
  }

  @PutMapping("/api/admin/locations/{locationCode}")
  @PreAuthorize(EDIT)
  public Location updateLocation(
      @PathVariable @Size(min = 1, max = 10) @Pattern(regexp = "^[A-Z0-9_-]+$") String locationCode,
      @Valid @RequestBody LocationRequest body) {
    return locations.update(locationCode, body);
  }

  @DeleteMapping("/api/admin/locations/{locationCode}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateLocation(
      @PathVariable @Size(min = 1, max = 10) @Pattern(regexp = "^[A-Z0-9_-]+$")
          String locationCode) {
    locations.deactivate(locationCode);
    return ResponseEntity.noContent().build();
  }

  // --- leave types -------------------------------------------------------------

  @GetMapping("/api/admin/leave-types")
  @PreAuthorize(VIEW)
  public List<LeaveType> listLeaveTypes(@RequestParam(required = false) @Nullable Boolean active) {
    return leaveTypes.list(active);
  }

  @PostMapping("/api/admin/leave-types")
  @PreAuthorize(EDIT)
  public ResponseEntity<LeaveType> createLeaveType(@Valid @RequestBody LeaveTypeRequest body) {
    LeaveType t = leaveTypes.create(body);
    return ResponseEntity.created(URI.create("/api/admin/leave-types/" + t.leaveTypeId())).body(t);
  }

  @GetMapping("/api/admin/leave-types/{leaveTypeId}")
  @PreAuthorize(VIEW)
  public LeaveType getLeaveType(@PathVariable @Min(1) int leaveTypeId) {
    return leaveTypes.get(leaveTypeId);
  }

  @PutMapping("/api/admin/leave-types/{leaveTypeId}")
  @PreAuthorize(EDIT)
  public LeaveType updateLeaveType(
      @PathVariable @Min(1) int leaveTypeId, @Valid @RequestBody LeaveTypeRequest body) {
    return leaveTypes.update(leaveTypeId, body);
  }

  @DeleteMapping("/api/admin/leave-types/{leaveTypeId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateLeaveType(@PathVariable @Min(1) int leaveTypeId) {
    leaveTypes.deactivate(leaveTypeId);
    return ResponseEntity.noContent().build();
  }

  // --- system parameters -------------------------------------------------------

  @GetMapping("/api/admin/system-parameters")
  @PreAuthorize(VIEW)
  public List<SystemParameter> listSystemParameters(
      @RequestParam(required = false)
          @Size(max = 50)
          @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
          @Nullable
          String group) {
    return parameters.list(group);
  }

  @PostMapping("/api/admin/system-parameters")
  @PreAuthorize(EDIT)
  public ResponseEntity<SystemParameter> createSystemParameter(
      @Valid @RequestBody SystemParameterRequest body) {
    SystemParameter p = parameters.create(body);
    return ResponseEntity.created(URI.create("/api/admin/system-parameters/" + p.paramId()))
        .body(p);
  }

  @GetMapping("/api/admin/system-parameters/{paramId}")
  @PreAuthorize(VIEW)
  public SystemParameter getSystemParameter(@PathVariable @Min(1) int paramId) {
    return parameters.get(paramId);
  }

  @PutMapping("/api/admin/system-parameters/{paramId}")
  @PreAuthorize(EDIT)
  public SystemParameter updateSystemParameter(
      @PathVariable @Min(1) int paramId, @Valid @RequestBody SystemParameterUpdateRequest body) {
    return parameters.update(paramId, body);
  }

  @DeleteMapping("/api/admin/system-parameters/{paramId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deleteSystemParameter(@PathVariable @Min(1) int paramId) {
    parameters.delete(paramId);
    return ResponseEntity.noContent().build();
  }
}
