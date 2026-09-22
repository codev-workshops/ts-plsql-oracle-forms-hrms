package com.acme.hrms.salary;

/**
 * Java integration seam for employee-service's {@code employee_history} writer. The salary module
 * publishes the event but never writes the employee-owned table; employee-service registers the
 * listener when that node is introduced.
 */
@FunctionalInterface
public interface SalaryChangeListener {

  void onSalaryChanged(SalaryChangeEvent event);
}
