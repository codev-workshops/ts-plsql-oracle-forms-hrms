/**
 * Performance module (Phase 3 slice). Ports PKG_PERFORMANCE, HRMS_PERFORMANCE form.
 *
 * <p>Owned by the Performance slice: only this package, {@code src/test/java/com/hrms/performance/},
 * {@code frontend/src/features/performance/} and Flyway migrations V400-V499 in {@code src/main/resources/db/migration/performance/} may be edited by that slice.
 * Use shared services from {@code com.hrms.platform} (EmployeeLookup, SalaryRecordService,
 * AuditService, NotificationService, ParameterService, BusinessDayCalendar, PermissionService,
 * Validators) and implement the endpoints already defined in docs/modernization/openapi-v1.yaml.
 * See docs/modernization/slice-recipe.md.
 */
package com.hrms.performance;
