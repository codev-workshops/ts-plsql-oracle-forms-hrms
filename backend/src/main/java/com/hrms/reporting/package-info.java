/**
 * Reporting module (Phase 3 slice). Ports PKG_REPORTING, reporting views.
 *
 * <p>Owned by the Reporting slice: only this package, {@code src/test/java/com/hrms/reporting/},
 * {@code frontend/src/features/reporting/} and Flyway migrations V600-V699 may be edited by that slice.
 * Use shared services from {@code com.hrms.platform} (EmployeeLookup, SalaryRecordService,
 * AuditService, NotificationService, ParameterService, BusinessDayCalendar, PermissionService,
 * Validators) and implement the endpoints already defined in docs/modernization/openapi-v1.yaml.
 * See docs/modernization/slice-recipe.md.
 */
package com.hrms.reporting;
