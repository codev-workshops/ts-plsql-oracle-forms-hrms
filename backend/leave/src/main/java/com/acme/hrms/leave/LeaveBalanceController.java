package com.acme.hrms.leave;

import com.acme.hrms.common.calendar.BusinessCalendar;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.common.security.CurrentCaller;
import com.acme.hrms.leave.LeaveAccess.Employee;
import com.acme.hrms.leave.LeaveDtos.BusinessDays;
import com.acme.hrms.leave.LeaveDtos.LeaveBalance;
import com.acme.hrms.leave.LeaveDtos.ObservedHoliday;
import com.acme.hrms.validation.dto.leave.BusinessDaysQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/leave/balances/mine} and {@code /api/leave/business-days} (pure reads). */
@RestController
public class LeaveBalanceController {

  private final LeaveBalanceRepository balances;
  private final LeaveAccess access;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public LeaveBalanceController(
      LeaveBalanceRepository balances, LeaveAccess access, BusinessCalendar calendar, Clock clock) {
    this.balances = balances;
    this.access = access;
    this.calendar = calendar;
    this.clock = clock;
  }

  @GetMapping("/api/leave/balances/mine")
  @PreAuthorize("isAuthenticated()")
  public List<LeaveBalance> getMyLeaveBalances(@RequestParam(required = false) Integer year) {
    if (year != null && (year < 2000 || year > 2099)) {
      throw new HrmsException(ErrorCode.VALIDATION_FAILED, "year must be 2000..2099", "year");
    }
    CallerIdentity caller = CurrentCaller.require();
    access.requireActive(caller.empId());
    return balances.listForEmployee(
        caller.empId(), year == null ? LocalDate.now(clock).getYear() : year);
  }

  @GetMapping("/api/leave/business-days")
  @PreAuthorize("isAuthenticated()")
  public BusinessDays getBusinessDays(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
    BusinessDaysQuery q = new BusinessDaysQuery();
    q.setStart(start);
    q.setEnd(end);
    access.validateWithDateOrder(q, "end");
    Employee emp = access.requireActive(CurrentCaller.require().empId());
    BusinessCalendar.Result r = calendar.businessDays(start, end, emp.locationCode());
    return new BusinessDays(
        r.start(),
        r.end(),
        r.businessDays(),
        r.holidays().stream()
            .map(h -> new ObservedHoliday(h.holidayName(), h.holidayDate(), h.observedDate()))
            .toList());
  }
}
