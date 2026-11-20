# Parity report (P8)

Every Phase 0 registry entry (checkpoint §5) with its final status. Commits: P1 `086b16f41d7d`, P2 `de31dad1ffd4`,
P3 `3de4b27a40f3`, P4 `2d4d2094c777`, P5 `f9d91aed89db`, P6 `2bf5a5c6326c`, P7 `3fb4e77c9b1a`/`c6d06ad12ce3`,
P8 `f9afac2`/`c56c9ab`/`c5a6dff`. Tests are under `backend/src/test/java/com/hrms/` (run `mvn -f backend/pom.xml test`).

**Tally: 117 entries (TRG 11, FRM 50, MNU 7, PLL 11, PKG 32, VW 6) — 100 re-homed, 14 dropped (with rationale), 3 blocked on open user decisions**
(TRG-011 → OQ-P5-1; PLL-003, PKG-S03 → OQ-P5-2). DEF rows (32) are decisions, listed in §2.

## 1. Registry

| ID | Final status | Evidence |
|---|---|---|
| TRG-001, TRG-005 | re-homed (P5) | `EmployeeCommandApiTest.createHires…`, `updateIsPartialAndAudited`; `AuditTrailTest` |
| TRG-002, TRG-003, TRG-004 | re-homed (P1/P5) | `EmployeeCommandApiTest.createValidations…`, `SchemaMigrationTest.activeEmailIsUniqueCaseInsensitively` |
| TRG-006, TRG-007, TRG-008 | re-homed (P1/P5) | `EmployeeValidatorsTest.terminatedToActiveOnlyThroughRehire`, `rehireIsTheOnlyWayBackFromTerminated`, `writesNeedEmployeeEdit…`, `SchemaMigrationTest.appRoleCannotDeleteAnything` |
| TRG-009 | re-homed (P6) | `AuditTrailTest.employeeLifecycleAndSalaryRecordsAreAudited` (one row per insert/end-date, DEF-20) |
| TRG-010 | re-homed (P3) | `LeaveApiTest.approveMovesPendingToUsedAndAuditsTheStatus`, `AuditTrailTest.leavePayrollAndPerformance…` |
| TRG-011 | **blocked (OQ-P5-1)** | No department write path exists; becomes dropped under the recommended default, re-homed via `AuditWriter` if an API is added |
| FRM-001…003, FRM-013, FRM-015, FRM-020 | re-homed (P2) | `AuthApiTest`, `ChangePasswordTest` |
| FRM-004 | dropped (P7) | Browser focus/tab order replace WNFI/KEY-NEXT-ITEM |
| FRM-010, FRM-012, FRM-014 | re-homed (P7) | `PermissionMatrixTest.menuFollowsTheLegacyGates`, `ReportApiTest.everyReportNeedsReportsView` |
| FRM-011 | dropped (P7, OQ-03) | Admin form never existed; out of scope |
| FRM-016 | dropped (P8) | "About" dialog is UI chrome; no server rule |
| FRM-030…041, FRM-043, FRM-045, FRM-046 | re-homed (P2/P5/P6) | `EmployeeCommandApiTest`, `EmployeeReadApiTest`, `EmployeeValidatorsTest`, `PayrollApiTest.salaryRecordsAreReadOnly…` |
| FRM-042 | dropped (P5, D-03) | No delete; DELETE is 405 |
| FRM-044 | re-homed (P7) | D-05 error banner in React |
| FRM-050…056 | re-homed (P3) | `LeaveApiTest` |
| FRM-060…065 | re-homed (P6) | `PayrollApiTest` |
| FRM-070…077 | re-homed (P4) | `PerformanceApiTest` |
| MNU-001, MNU-002, MNU-005, MNU-007 | re-homed (P2/P7) | `menuFollowsTheLegacyGates`, React routes |
| MNU-003, MNU-004 | dropped (P7, OQ-03) | Admin out of scope |
| MNU-006 | dropped (P7) | Browser navigation replaces File/Edit/Query menus |
| PLL-001, PLL-002, PLL-004 | re-homed (P5) | `EmployeeValidatorsTest` |
| PLL-003 | **blocked (OQ-P5-2)** | Validator ready (`ssnUsesTheStricterPllRule`); no SSN route until key decision |
| PLL-005 | re-homed (P5/P6) | `SALARY_OUTSIDE_GRADE` warning |
| PLL-010 | dropped (P8) | Replaced by D-05 problem bodies + server logging |
| PLL-011 | dropped (P8) | Forms toolbar built-ins; React forms/buttons |
| PLL-012, PLL-015 | dropped (P7) | ISO-8601 + lookups per screen |
| PLL-013, PLL-014 | re-homed (P2) | `HrmsPrincipal`, `BearerTokenFilter` |
| PKG-E01…E08 | re-homed (P2/P5/P7) | `EmployeeCommandApiTest`, `EmployeeReadApiTest.searchIsInjectionSafe`, `ReportApiTest.orgChart…` |
| PKG-L01…L04 | re-homed (P3) | `LeaveApiTest`, `LeaveAccrualAndOutboxTest` |
| PKG-L05 | **re-homed (P8)** | `LeaveCarryoverService` + V500 + `ScheduledJobs`; `LeaveCarryoverTest` |
| PKG-P01…P05, PKG-P07 | re-homed (P6) | `PayrollApiTest`, `TaxCalculatorTest` |
| PKG-P06 | dropped (P8, OQ-03) | Pay register CSV deferred to a follow-up; payslips/run endpoints carry the same figures |
| PKG-F01…F03 | re-homed (P4) | `PerformanceApiTest` |
| PKG-S01, PKG-S02 | re-homed (P2) | `AuthApiTest`, `PermissionMatrixTest`, `ChangePasswordTest` |
| PKG-S03 | **blocked (OQ-P5-2)** | MD5 dropped (P2); SSN key + re-encryption pending (runbook §0) |
| PKG-A01 | **re-homed (P8 verified)** | `AuditWriter` everywhere; `AuditTrailTest` (all modules, actor/clock, failed audit rolls back) |
| PKG-A02 | **re-homed (P8)** | `AuditRetentionService`, V500 D-03 exception (A-P8-02); off by default (OQ-P8-1); `AuditTrailTest.retentionPurge…` |
| PKG-N01 | **re-homed (P3/P8)** | Outbox + dispatcher (P3); `SmtpNotificationSender` behind `hrms.notifications.sender=smtp`; `NotificationSenderSelectionTest` |
| PKG-C01 | re-homed (P3/P5) | Business days, validators; `set_param`/fiscal-year/formatting dropped with Admin (OQ-03) and UI formatting |
| PKG-R01 | dropped (P8, OQ-03) | The 4 named views are exposed (P7); the other 7 reports are a follow-up |
| PKG-I01 | dropped (P8, OQ-13) | Integrations out of scope; tables migrated |
| VW-001…VW-006 | re-homed (P1/P2/P7) | `SchemaMigrationTest.reportingViewsAreQueryable`, `ReportApiTest` |

## 2. Port-or-fix decisions (OQ-04: fix, with a test documenting the legacy difference)

| ID | Decision | Test |
|---|---|---|
| DEF-01 half-day overlap | **Fixed** (P3): AM/PM aware, half day = one date | `LeaveApiTest.overlapIsHalfDayPeriodAware` |
| DEF-02 observed holidays | **Fixed through data** (P8): observed-date rows; precheck WARN for weekend holidays without one | `ObservedHolidayParityTest` |
| DEF-03 carryover double-expire | **Fixed** (P8): idempotent runs per year/date; expire only unused carryover | `LeaveCarryoverTest` |
| DEF-04 email drift | **Fixed** (P5): one server regex; precheck WARN for legacy addresses | `EmployeeValidatorsTest.emailUsesTheServerRegexForEveryCaller`, `DataMigrationRehearsalTest` |
| DEF-05, DEF-06, DEF-16, DEF-20, DEF-P6-1…3 | Fixed (P6) | `PayrollApiTest`, `TaxCalculatorTest` |
| DEF-07 | Not applicable (no overtime code) | — |
| DEF-08, DEF-13, DEF-14, DEF-22, DEF-P5-1, DEF-P5-2 | Fixed (P5/P6) | `EmployeeCommandApiTest` |
| DEF-09, DEF-10, DEF-11, DEF-17 | Fixed (P1/P2, D-02/D-04/D-06) | `EmployeeReadApiTest.searchIsInjectionSafe`, `AuthApiTest`; DEF-11 key: OQ-P5-2 |
| DEF-12, DEF-15 | Fixed (P3) | `LeaveApiTest.noApprovalTypesAreApprovedOnSubmit…`, `approveMoves…AuditsTheStatus` |
| DEF-18 | Dropped with Forms | — |
| DEF-19 | Decided by OQ-10 (180 days) | `createValidations…` |
| DEF-21 | Fixed (P4/P6) | `PerformanceApiTest`, `PayrollApiTest` |
| DEF-23 | Provisional (OQ-14): both values returned | `ReportApiTest.leaveSummary…` |
| DEF-24 | Fixed (P1); precheck BLOCKING for violating rows | `SchemaMigrationTest.checkConstraints…`, `DataMigrationRehearsalTest` |
| DEF-25 | Informational | — |
| DEF-P6-4, DEF-P6-5 | Parity kept (OQ-P6-1, OQ-P6-2 open) | `TaxCalculatorTest` |
