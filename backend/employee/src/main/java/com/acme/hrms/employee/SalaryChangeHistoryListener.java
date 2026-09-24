package com.acme.hrms.employee;

import com.acme.hrms.employee.EmployeeHistoryRepository.HistoryRow;
import com.acme.hrms.salary.SalaryChangeEvent;
import com.acme.hrms.salary.SalaryChangeListener;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code x-history: [SALARY_CHANGE]} row for {@code POST /api/employees/{id}/salary}.
 * Runs inside the salary transaction (ARCH-01: salary-module owns SALARY_RECORDS, this module owns
 * {@code employee_history}), so a failed change rolls back both rows. Initial salaries are part of
 * the caller's {@code HIRE} row and are ignored here.
 */
@Component
public class SalaryChangeHistoryListener implements SalaryChangeListener {

  private final EmployeeHistoryRepository history;

  public SalaryChangeHistoryListener(EmployeeHistoryRepository history) {
    this.history = history;
  }

  @Override
  public void onSalaryChanged(SalaryChangeEvent event) {
    if (event.kind() != SalaryChangeEvent.Kind.CHANGE) {
      return;
    }
    history.insert(
        new HistoryRow(
            event.empId(),
            "SALARY_CHANGE",
            event.effectiveDate(),
            null,
            null,
            null,
            null,
            null,
            null,
            event.oldSalary(),
            event.newSalary(),
            null,
            null,
            event.reason(),
            null,
            event.actor()));
  }
}
