/**
 * Payroll module (Phase 3 slice). Ports PKG_PAYROLL, PKG_INTEGRATION, HRMS_PAYROLL form.
 *
 * <p>Owned by the Payroll slice: only this package, {@code src/test/java/com/hrms/payroll/},
 * {@code frontend/src/features/payroll/} and Flyway migrations V300-V399 may be edited by that slice.
 * Use shared services from {@code com.hrms.platform} (EmployeeLookup, SalaryRecordService,
 * AuditService, NotificationService, ParameterService, BusinessDayCalendar, PermissionService,
 * Validators) and implement the endpoints already defined in docs/modernization/openapi-v1.yaml.
 * See docs/modernization/slice-recipe.md.
 */
package com.hrms.payroll;
