package com.hrms.employee;

import com.hrms.employee.EmployeeModels.Dependent;
import com.hrms.employee.EmployeeModels.EmergencyContact;
import com.hrms.employee.EmployeeModels.EmployeeCreate;
import com.hrms.employee.EmployeeModels.EmployeeDetail;
import com.hrms.employee.EmployeeModels.EmployeeHistory;
import com.hrms.employee.EmployeeModels.EmployeeUpdate;
import com.hrms.employee.EmployeeModels.OrgChartNode;
import com.hrms.employee.EmployeeModels.PromoteRequest;
import com.hrms.employee.EmployeeModels.RehireRequest;
import com.hrms.employee.EmployeeModels.TerminateRequest;
import com.hrms.employee.EmployeeModels.TransferRequest;
import com.hrms.platform.auth.CurrentUser;
import com.hrms.platform.employee.EmployeeSummary;
import com.hrms.platform.salary.SalaryRecord;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    public List<EmployeeSummary> search(CurrentUser user, @RequestParam(required = false) String lastName,
                                        @RequestParam(required = false) String firstName,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) Long locationId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hiredFrom,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hiredTo,
                                        @RequestParam(required = false) Integer limit) {
        return employees.search(user, lastName, firstName, deptId, locationId, status, hiredFrom, hiredTo, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeDetail create(CurrentUser user, @Valid @RequestBody EmployeeCreate body) {
        return employees.create(user, body);
    }

    @GetMapping("/{empId}")
    public EmployeeDetail get(CurrentUser user, @PathVariable long empId) {
        return employees.get(user, empId);
    }

    @PatchMapping("/{empId}")
    public EmployeeDetail update(CurrentUser user, @PathVariable long empId, @Valid @RequestBody EmployeeUpdate body) {
        return employees.update(user, empId, body);
    }

    @GetMapping("/by-number/{empNumber}")
    public EmployeeDetail byNumber(CurrentUser user, @PathVariable String empNumber) {
        return employees.getByNumber(user, empNumber);
    }

    @PostMapping("/{empId}/transfer")
    public EmployeeDetail transfer(CurrentUser user, @PathVariable long empId,
                                   @Valid @RequestBody TransferRequest body) {
        return employees.transfer(user, empId, body);
    }

    @PostMapping("/{empId}/promote")
    public EmployeeDetail promote(CurrentUser user, @PathVariable long empId, @Valid @RequestBody PromoteRequest body) {
        return employees.promote(user, empId, body);
    }

    @PostMapping("/{empId}/terminate")
    public EmployeeDetail terminate(CurrentUser user, @PathVariable long empId,
                                    @Valid @RequestBody TerminateRequest body) {
        return employees.terminate(user, empId, body);
    }

    @PostMapping("/{empId}/rehire")
    public EmployeeDetail rehire(CurrentUser user, @PathVariable long empId, @Valid @RequestBody RehireRequest body) {
        return employees.rehire(user, empId, body);
    }

    @GetMapping("/{empId}/direct-reports")
    public List<EmployeeSummary> directReports(CurrentUser user, @PathVariable long empId) {
        return employees.directReports(user, empId);
    }

    @GetMapping("/{empId}/org-chart")
    public List<OrgChartNode> orgChart(CurrentUser user, @PathVariable long empId) {
        return employees.orgChart(user, empId);
    }

    @GetMapping("/{empId}/history")
    public List<EmployeeHistory> history(CurrentUser user, @PathVariable long empId) {
        return employees.history(user, empId);
    }

    @GetMapping("/{empId}/salary-history")
    public List<SalaryRecord> salaryHistory(CurrentUser user, @PathVariable long empId) {
        return employees.salaryHistory(user, empId);
    }

    @GetMapping("/{empId}/dependents")
    public List<Dependent> dependents(CurrentUser user, @PathVariable long empId) {
        return employees.dependents(user, empId);
    }

    @PostMapping("/{empId}/dependents")
    @ResponseStatus(HttpStatus.CREATED)
    public Dependent addDependent(CurrentUser user, @PathVariable long empId, @Valid @RequestBody Dependent body) {
        return employees.addDependent(user, empId, body);
    }

    @PutMapping("/{empId}/dependents/{dependentId}")
    public Dependent updateDependent(CurrentUser user, @PathVariable long empId, @PathVariable long dependentId,
                                     @Valid @RequestBody Dependent body) {
        return employees.updateDependent(user, empId, dependentId, body);
    }

    @DeleteMapping("/{empId}/dependents/{dependentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDependent(CurrentUser user, @PathVariable long empId, @PathVariable long dependentId) {
        employees.deleteDependent(user, empId, dependentId);
    }

    @GetMapping("/{empId}/emergency-contacts")
    public List<EmergencyContact> emergencyContacts(CurrentUser user, @PathVariable long empId) {
        return employees.emergencyContacts(user, empId);
    }

    @PostMapping("/{empId}/emergency-contacts")
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyContact addEmergencyContact(CurrentUser user, @PathVariable long empId,
                                                @Valid @RequestBody EmergencyContact body) {
        return employees.addEmergencyContact(user, empId, body);
    }

    @PutMapping("/{empId}/emergency-contacts/{contactId}")
    public EmergencyContact updateEmergencyContact(CurrentUser user, @PathVariable long empId,
                                                   @PathVariable long contactId,
                                                   @Valid @RequestBody EmergencyContact body) {
        return employees.updateEmergencyContact(user, empId, contactId, body);
    }

    @DeleteMapping("/{empId}/emergency-contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmergencyContact(CurrentUser user, @PathVariable long empId, @PathVariable long contactId) {
        employees.deleteEmergencyContact(user, empId, contactId);
    }
}
