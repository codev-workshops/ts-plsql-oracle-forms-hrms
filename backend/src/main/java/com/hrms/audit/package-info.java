/**
 * Audit module (Phase 3 slice). Ports PKG_AUDIT read/purge, audit log viewer.
 *
 * <p>Owned by the Audit slice: only this package, {@code src/test/java/com/hrms/audit/},
 * {@code frontend/src/features/audit/} and Flyway migrations V500-V599 in {@code src/main/resources/db/migration/audit/} may be edited by that slice.
 * Use shared services from {@code com.hrms.platform} (EmployeeLookup, SalaryRecordService,
 * AuditService, NotificationService, ParameterService, BusinessDayCalendar, PermissionService,
 * Validators) and implement the endpoints already defined in docs/modernization/openapi-v1.yaml.
 * See docs/modernization/slice-recipe.md.
 */
package com.hrms.audit;
