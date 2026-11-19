/**
 * Employee module (Phase 3 slice). Ports PKG_EMPLOYEE, HRMS_EMPLOYEE form, employee triggers.
 *
 * <p>Owned by the Employee slice: only this package, {@code src/test/java/com/hrms/employee/},
 * {@code frontend/src/features/employee/} and Flyway migrations V200-V299 may be edited by that slice.
 * Use shared services from {@code com.hrms.platform} (EmployeeLookup, SalaryRecordService,
 * AuditService, NotificationService, ParameterService, BusinessDayCalendar, PermissionService,
 * Validators) and implement the endpoints already defined in docs/modernization/openapi-v1.yaml.
 * See docs/modernization/slice-recipe.md.
 */
package com.hrms.employee;
