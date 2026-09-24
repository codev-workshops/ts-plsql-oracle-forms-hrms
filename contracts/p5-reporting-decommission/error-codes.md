# P5 Reporting / Integration / Admin – `ApiError.code` contract

Source of truth for legacy numbers: `COMPONENT_MAPPING.md` §11. This file copies the
Phase 5 subset (`-20001`, `-20003` / `-20011`) and adds HTTP status, triggering rule, raising
component and `field`. **§11 was extended by this contract PR** with two new rows – the
admin range `-20601` … `-20606` and the integration range `-20701` … `-20704` – because
`PKG_REPORTING.pkb` / `PKG_INTEGRATION.pkb` raise **no** `RAISE_APPLICATION_ERROR` at all
(every failure is swallowed by `WHEN OTHERS → PKG_COMMON.log_error`) and `HRMS_ADMIN.fmb`
relied on database constraints (`uk_dept_code`, `chk_salary_range`, …) surfacing as raw
`ORA-00001` / `ORA-02290`. The new numbers follow the §11 convention (one hundred per
module; `-206xx` admin, `-207xx` integration/batch) and have **no legacy counterpart** –
they are marked `legacy_source=none` in the scenario registry and only the target side is
diffed (TEST_STRATEGY.md §5 row 5, GOLDEN-ORACLE MODE = OFF).

`code` is transmitted as a **string** carrying the Oracle `SQLCODE` verbatim (`"-20003"`)
so Level 2 parallel-run diffs (`legacy_source=recorded`) can compare it with the
`ORA-20xxx` recorded from `PKG_EMPLOYEE.pkb`.

## 1. Legacy codes this module may return (copied from §11)

| `code` | HTTP | Raised by (target) | Legacy origin | Triggering rule | `field` | Routes |
|---|---|---|---|---|---|---|
| `-20001` | 404 | `EmployeeLookup` (shared, `employee-module`) | `PKG_EMPLOYEE` / `PKG_LEAVE` `NO_DATA_FOUND` → `-20001` "Employee not found / not active" | A **subject** employee reference does not resolve to an `EMPLOYEES` row with `EMPLOYMENT_STATUS='ACTIVE'`: `rootEmpId` of the hierarchy report, `managerEmpId` of a department, `emp_number` of a time-attendance line (per-line verdict, whole file `-20704` when none is accepted). Never raised for the actor (`jwt.empId` is trusted). | `rootEmpId` / `managerEmpId` / `line N` | `GET /api/reports/org-hierarchy[.csv]`, `POST/PUT /api/admin/departments…`, `POST /api/integration/time-attendance/import` |
| `-20003` | 400 | `DepartmentService.validateParent` (`admin-module`) | `PKG_EMPLOYEE.validate_dept` `IF v_count = 0 THEN RAISE_APPLICATION_ERROR(-20003, 'Invalid or inactive department: ' \|\| p_dept_id)` | `parentDeptId` (or a report `deptId` filter) matches no `DEPARTMENTS` row with `ACTIVE_FLAG='Y'`. Message frozen: `Invalid or inactive department: {id}`. | `parentDeptId` / `deptId` | `POST/PUT /api/admin/departments…`, every report with `deptId` |
| `-20011` | 400 | `JobTitleService` / report filters (`admin-module`, `reporting-module`) | `PKG_EMPLOYEE.validate_employee` `WHEN NO_DATA_FOUND THEN RAISE_APPLICATION_ERROR(-20011, 'Invalid or inactive job: ' \|\| p_job_id)` | A job reference matches no `JOB_TITLES` row with `ACTIVE_FLAG='Y'` (reserved for the admin routes that key on a job, e.g. future `jobId` filters; the reference-data grids themselves key on `gradeId` → `-20604`). Message frozen: `Invalid or inactive job: {id}`. | `jobId` | admin routes keyed on a job |

## 2. New admin / integration codes (added to §11 by this PR)

| `code` | HTTP | Raised by (target) | Triggering rule | `field` | Routes |
|---|---|---|---|---|---|
| `-20601` | 409 | `ReferenceDataService.create/update` | Natural code already exists (`uk_dept_code`, `uk_grade_code`, `uk_job_code`, `pk_locations`, `uk_leave_type_code`, `(param_group, param_code)`), **or** the body's code differs from the stored code on `PUT` (codes are immutable). Message: `Reference code already exists: {code}` / `Reference code is immutable: {code}`. | `deptCode` / `gradeCode` / `jobCode` / `locationCode` / `leaveTypeCode` / `paramCode` | every admin `POST` / `PUT` |
| `-20602` | 422 | `ReferenceDataService.deactivate` | Cannot deactivate – active dependants reference the row: department → active employees or active child departments; grade → active job titles; job title → active employees; location → active employees or departments; leave type → `PENDING` leave requests. Message: `{Entity} {code} has {n} active {dependants}`. | `null` | every admin `DELETE` except system parameters |
| `-20603` | 400 | `JobGradeService`, `LeaveTypeService`, `SystemParameterService` | Value rule of the addressed row: `maxSalary < minSalary` (`CHK_SALARY_RANGE`); `carryoverMax > maxBalance`; `paramValue` does not parse as `dataType`. | `maxSalary` / `carryoverMax` / `paramValue` | `POST/PUT /api/admin/job-grades…`, `…/leave-types…`, `…/system-parameters…` |
| `-20604` | 400 | `JobTitleService`, `DepartmentService` | Referenced grade or location is unknown or inactive (`gradeId` on a job title, `locationCode` on a department). Message: `Invalid or inactive grade: {id}` / `Invalid or inactive location: {code}`. | `gradeId` / `locationCode` | `POST/PUT /api/admin/job-titles…`, `…/departments…` |
| `-20605` | 400 | `DepartmentService.validateParent` | `parentDeptId` chain revisits the department being written (cycle), or `parentDeptId == deptId`. | `parentDeptId` | `POST/PUT /api/admin/departments…` |
| `-20606` | 422 | `SystemParameterService.update/delete` | `SYSTEM_PARAMETERS.EDITABLE_FLAG='N'`. | `null` | `PUT/DELETE /api/admin/system-parameters/{paramId}` |
| `-20701` | 422 | `GlFeedService.generate` | `PAYROLL_RUNS.STATUS` not in (`APPROVED`, `PAID`). Message: `Cannot export GL feed for run in status: {status}`. | `runId` | `POST /api/integration/gl-feed` |
| `-20702` | 409 | `LeaveJobTrigger` (`admin-module` → `leave-module`) | An accrual job for the same `accrualDate` / a carryover job for the same `year` is `RUNNING`. | `accrualDate` / `year` | `POST /api/admin/leave/accrual`, `…/carryover` |
| `-20703` | per-line (422 body via `-20704` when total) | `TimeAttendanceImportService` | Duplicate `(emp_number, date)` inside one uploaded file. | `line N` | `POST /api/integration/time-attendance/import` |
| `-20704` | 422 | `TimeAttendanceImportService` | No line of the uploaded file was accepted; `details[]` carries the first 100 line errors (`-20001`, `-20703`, `VALIDATION_FAILED`). | `file` | `POST /api/integration/time-attendance/import` |

## 3. Framework codes (not `-20xxx`, not in §11)

| `code` | HTTP | Rule |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation on any DTO / query parameter (`validation-schema.json` module `p5-reporting-decommission`); `details[]` lists all violations; `to < from` on the audit-log search. |
| `TOKEN_INVALID` | 401 | P0 token filter. |
| `FORBIDDEN` | 403 | `@PreAuthorize` denied (`REPORTS:VIEW`, `PAYROLL:VIEW`, `PAYROLL:APPROVE`, `PAYROLL:EDIT`, `ADMIN:VIEW`, `ADMIN:EDIT`, `LEAVE:ADMIN`, `EMPLOYEE:VIEW`; `integrationAccess.canDownload`). |
| `NOT_ACCEPTABLE` | 406 | `Accept` is neither `application/json` nor `text/csv` on a report / audit route. |
| `REFERENCE_NOT_FOUND` | 404 | Path id / code of an admin route matches no row. |
| `PERIOD_NOT_FOUND`, `RUN_NOT_FOUND` | 404 | P4 codes reused for `periodId` / `runId`. |
| `JOB_NOT_FOUND` | 404 | Unknown leave job id. |
| `FILE_NOT_FOUND` | 404 | Unknown `fileId`, or the object is missing from storage. |
| `PAYLOAD_TOO_LARGE` | 413 | Upload > 5 MiB. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Upload part is not `text/csv`. |
| `INTERNAL_ERROR` | 500 | Unhandled; `traceId` correlates with `error_log`. Legacy `PKG_COMMON.log_error` rows are **not** reproduced – `error_log` is written by the P0 `@ControllerAdvice`. |
