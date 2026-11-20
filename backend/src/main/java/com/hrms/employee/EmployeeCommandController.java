package com.hrms.employee;

import com.hrms.auth.HrmsPrincipal;
import com.hrms.common.ApiException;
import com.hrms.common.ErrorCode;
import com.hrms.employee.EmployeeCommandService.Caller;
import com.hrms.employee.EmployeeModels.CreateEmployeeRequest;
import com.hrms.employee.EmployeeModels.EmployeeHistoryEntry;
import com.hrms.employee.EmployeeModels.EmployeeMutation;
import com.hrms.employee.EmployeeModels.PromoteRequest;
import com.hrms.employee.EmployeeModels.RehireRequest;
import com.hrms.employee.EmployeeModels.TerminateRequest;
import com.hrms.employee.EmployeeModels.TransferRequest;
import com.hrms.employee.EmployeeModels.UpdateEmployeeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee commands (contract v1.1-P5). Every write needs EMPLOYEE:EDIT (FRM-030); the Forms direct-table DML path
 * is gone (FRM-041) and there is no physical delete (D-03, TRG-008).
 */
@RestController
@RequestMapping("/api/v1/employees")
@PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
public class EmployeeCommandController {

    private final EmployeeCommandService commands;

    public EmployeeCommandController(EmployeeCommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    ResponseEntity<EmployeeMutation> create(@AuthenticationPrincipal HrmsPrincipal p,
            @Valid @RequestBody CreateEmployeeRequest body, HttpServletRequest http) {
        EmployeeMutation m = commands.create(new Caller(p, http.getRemoteAddr()), body);
        return ResponseEntity.created(URI.create("/api/v1/employees/" + m.employee().empId())).body(m);
    }

    @PatchMapping("/{empId}")
    EmployeeMutation update(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long empId,
            @Valid @RequestBody UpdateEmployeeRequest body, HttpServletRequest http) {
        return commands.update(new Caller(p, http.getRemoteAddr()), empId, body);
    }

    @PostMapping("/{empId}/transfer")
    EmployeeMutation transfer(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long empId,
            @Valid @RequestBody TransferRequest body, HttpServletRequest http) {
        return commands.transfer(new Caller(p, http.getRemoteAddr()), empId, body);
    }

    @PostMapping("/{empId}/promote")
    EmployeeMutation promote(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long empId,
            @Valid @RequestBody PromoteRequest body, HttpServletRequest http) {
        return commands.promote(new Caller(p, http.getRemoteAddr()), empId, body);
    }

    @PostMapping("/{empId}/terminate")
    EmployeeMutation terminate(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long empId,
            @Valid @RequestBody TerminateRequest body, HttpServletRequest http) {
        return commands.terminate(new Caller(p, http.getRemoteAddr()), empId, body);
    }

    @PostMapping("/{empId}/rehire")
    EmployeeMutation rehire(@AuthenticationPrincipal HrmsPrincipal p, @PathVariable long empId,
            @Valid @RequestBody RehireRequest body, HttpServletRequest http) {
        return commands.rehire(new Caller(p, http.getRemoteAddr()), empId, body);
    }

    @GetMapping("/{empId}/history")
    List<EmployeeHistoryEntry> history(@PathVariable long empId) {
        return commands.history(empId);
    }

    /** TRG-008 / D-03: employees are never physically deleted; leave through terminate. */
    @DeleteMapping("/{empId}")
    @PreAuthorize("hasAuthority('EMPLOYEE:VIEW')")
    void delete(@PathVariable long empId) {
        throw new ApiException(ErrorCode.DELETE_FORBIDDEN,
                "Direct deletion not allowed. Use the termination process.");
    }
}
