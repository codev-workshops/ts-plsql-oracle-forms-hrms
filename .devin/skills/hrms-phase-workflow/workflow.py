"""HRMS modernization – phase workflow (P0 foundation, P1–P5).

Every phase runs the same reusable sub-graph (`run_phase`):

    contract ──┬── backend  ──┐
               └── frontend ──┴── fan-in (integration-merge branch) ── integration session ── gate
                                                                                              ├─ pass → [manual approvals] → promote → next phase contract
                                                                                              └─ fail → remediation (backend | frontend | both) → rebase merge → re-run integration

Phase-specific deviations are data, not code branches: see PHASES below
(P3 backend is two sequential nodes; P4 integration is PayrollShadowRunner
shadow mode; P0/P1/P2/P3/P4/P5 have manual-approval nodes that the workflow
never auto-advances).

Runtime primitives `agent`, `parallel`, `pipeline`, `log`, `register_workflow`
and `WorkflowAgentError` are injected by the dynamic-workflow runtime; do not
redefine them.  Run with the `run_workflow` tool; see WORKFLOW_README.md.
"""

import asyncio
import json
import os

# --------------------------------------------------------------------------
# Operator configuration
# --------------------------------------------------------------------------

REPO = "codev-workshops/ts-plsql-oracle-forms-hrms"
REPO_URL = f"https://github.com/{REPO}"

# Branch that carries the reference documents and from which phase/p0-foundation is cut.
BASE_BRANCH = os.environ.get("HRMS_WF_BASE_BRANCH", "devin/1789629102-hrms-analysis-artifacts")

# Phases to run in this invocation (subset of P0..P5, in order). Earlier phases
# must already be promoted; the workflow verifies the previous phase branch exists.
PHASES_TO_RUN = [p for p in os.environ.get("HRMS_WF_PHASES", "P0,P1,P2,P3,P4,P5").split(",") if p]

# Manual approvals already granted out of band (comma separated gate ids, e.g.
# "P1.bake-4-weeks,P4.shadow-gate"). Approved gates skip the approval node.
# This is how a run that stopped at a calendar bake is resumed: grant the
# gate here, re-run with the same run_id, everything else replays.
APPROVED_GATES = {g for g in os.environ.get("HRMS_WF_APPROVED_GATES", "").split(",") if g}

MAX_REMEDIATION_ROUNDS = 3

REF_DOCS = (
    "CUTOVER_PLAN.md (phasing/gates §4–§10), TEST_STRATEGY.md (levels §2, gate matrix §5, tooling §7), "
    "COMPONENT_MAPPING.md (per-module FE/BE targets §1–§8, error-code contract §11), "
    "MODERNIZATION_BLUEPRINT.md (§8 area strategy, §9 design rules, §10 PostgreSQL surface)"
)

GLOBAL_RULES = f"""
Non-negotiable design rules (MODERNIZATION_BLUEPRINT.md §9, decisions A/B):
- Target is Spring Boot (Java) on PostgreSQL + React. No PL/SQL, PL/pgSQL, triggers or .pll in the target.
  Legacy PKG_* / VW_* stay on Oracle only as the characterization / reconciliation oracle.
- ApiError {{code, message, field?, traceId}}; `code` carries the legacy -20xxx number (COMPONENT_MAPPING.md §11).
- Identity from the JWT claim, never from a client-supplied empId. One owning Java module per table (ARCH-01/02).
- Validation is defined once in hrms-validation and exported to frontend/src/generated/validation-schema.json.
- Every gate is Level 1 + Level 2 + Level 3 of TEST_STRATEGY.md §5; never weaken a gate to make it pass.
Repository: {REPO_URL}. Reference documents: {REF_DOCS}. Read them before you start.
""".strip()


# --------------------------------------------------------------------------
# Phase catalogue – the per-phase sub-graph is instantiated from this data
# --------------------------------------------------------------------------

PHASES = {
    "P0": {
        "id": "P0",
        "slug": "p0-foundation",
        "title": "Phase 0 – Foundation (auth-service, proxy + SSO bridge, app shell, shared modules, golden oracle, PostgreSQL schema, CDC)",
        "plan_sections": "CUTOVER_PLAN.md §4; TEST_STRATEGY.md §3, §5 row 0; COMPONENT_MAPPING.md §1, §2, §7, §8, §9",
        "contract": {
            "endpoints": [
                "POST /api/auth/login, POST /api/auth/logout, POST /api/auth/refresh, GET /api/auth/me, PUT /api/auth/password (COMPONENT_MAPPING.md §1)",
                "GET /api/reference/departments|job-titles|locations|leave-types?active=true (COMPONENT_MAPPING.md §3.3, §5)",
                "GET /api/employees?status=ACTIVE&fields=id,name,jobTitle&q= (manager search, §3.3)",
                "SSO bridge: POST /legacy/sso/exchange (JWT → Forms USER_SESSIONS row) per CUTOVER_PLAN.md §2 and §4.2 item 0.8",
            ],
            "error_codes": ["-20301", "-20310", "-20311", "-20312", "-20001 (employee not found / inactive, shared)"],
            "extra": [
                "ApiError envelope and @ControllerAdvice mapping contract (COMPONENT_MAPPING.md §7 handle_error row)",
                "validation-schema.json format (generator output shape, versioning field, snapshot-test contract; TEST_STRATEGY.md §2.1)",
                "Reverse-proxy module flag names/values: employee, payroll, payroll.engine, leave, performance, reporting (CUTOVER_PLAN.md §2 rule 2)",
            ],
        },
        "backend": {
            "scope": [
                "Repository/build foundation: backend/ Gradle or Maven multi-module (hrms-common, hrms-validation, hrms-audit, hrms-notification, auth, reference); Testcontainers PostgreSQL; Flyway baseline V1__hrms_schema.sql translating schema/ to PostgreSQL per MODERNIZATION_BLUEPRINT.md §10 (unquoted lower-case identifiers, SEQ_* → sequences CACHE 1, GENERATED ALWAYS … STORED for LEAVE_BALANCES.AVAILABLE, no triggers)",
                "Golden oracle on Oracle: tests/utplsql/ characterization suites for PKG_SECURITY, PKG_EMPLOYEE, PKG_PAYROLL, PKG_LEAVE, PKG_PERFORMANCE against the frozen seed (tools/fixtures/); tests/golden/views-baseline.csv for the six VW_* views (TEST_STRATEGY.md §3, CUTOVER_PLAN.md §4.2 items 0.1, 0.2)",
                "PostgreSQL reconciliation equivalents: tests/reconciliation/pg/ six queries mirroring VW_EMPLOYEE_DIRECTORY, VW_ORG_HIERARCHY (WITH RECURSIVE), VW_EMPLOYEE_COMPENSATION, VW_LEAVE_SUMMARY, VW_PAYROLL_LATEST, VW_PENDING_APPROVALS; tools/reconcile/ runner (CUTOVER_PLAN.md §4.2 item 0.5b)",
                "CDC/sync tooling Oracle → PostgreSQL for not-yet-migrated modules plus reverse extract for rollback (CUTOVER_PLAN.md §2, §4.2 item 0.5a)",
                "auth-service: Spring Security, JWT + refresh, BCrypt/Argon2 PasswordEncoder, ROLES/PERMISSIONS authorities, lockout/complexity rules from PKG_SECURITY (SEC-01/02/05/07/08 fixed – document the deliberate divergence), FieldEncryptionService (AES-GCM, not pgcrypto)",
                "Reverse proxy config with per-module flags, SSO bridge (JWT → legacy session), reference endpoints",
                "tools/parallel-run/ harness skeleton (Java: utPLSQL runner + REST runner + diff report) so later phases only add scenarios",
                "Level 1: JUnit 5 for auth/common/validation/audit/notification; PostgreSQL repository tests for the Flyway baseline (seed loads, constraints hold)",
            ],
            "l2_wiring": "Register the harness itself and the auth/SSO smoke scenarios (login, refresh, password-change rules -20310…-20312, lockout) in tools/parallel-run/.",
        },
        "frontend": {
            "pages": [
                "React app shell (COMPONENT_MAPPING.md §2, §7): AuthContext, ProtectedRoute, AppShell/Toolbar, useErrorHandler + ErrorBoundary, ReferenceDropdown (React Query), format.ts (MM/DD/YYYY masks), axios interceptor (401 → refresh → /login)",
                "LoginPage + password-change page (COMPONENT_MAPPING.md §1); home tiles filtered by authorities; legacy module tiles link through the proxy to Forms until each module is promoted",
                "Validation-schema consumer: frontend/src/generated/validation-schema.json loader + Zod/Yup adapter + snapshot test",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path: login as seeded users of each role → tiles match authorities → open a legacy tile through the SSO bridge (Forms session created) → logout; 401/refresh flow.",
            "l2": "tools/parallel-run auth/SSO smoke scenarios (Oracle PKG_SECURITY vs auth-service) – only the deliberate SEC-05/07/08 divergences may differ and must be listed as expected diffs.",
            "l3": "Seed counts identical on Oracle and PostgreSQL for all 30 tables; six PostgreSQL reconciliation queries equal tests/golden/views-baseline.csv row-for-row (TEST_STRATEGY.md §5 row 0).",
            "extra": "All utPLSQL characterization suites green on Oracle; Flyway baseline applies cleanly on an empty PostgreSQL; CDC round-trip smoke (insert on Oracle appears on PostgreSQL).",
        },
        "approvals": [
            {
                "gate_id": "P0.security-signoff",
                "title": "Security sign-off of the SEC-01/02/05/07/08 auth divergences",
                "text": "CUTOVER_PLAN.md §4.3: the security owner signs off that auth-service intentionally diverges from PKG_SECURITY (plain-text/SHA-1 password paths, lockout, session handling). Not time-based, but a human decision.",
            }
        ],
        "post_promotion": "Nothing goes live for end users; the proxy still routes all modules to Forms. The promoted phase branch is the base for P1.",
    },
    "P1": {
        "id": "P1",
        "slug": "p1-performance",
        "title": "Phase 1 – Performance (performance-service + React performance pages)",
        "plan_sections": "CUTOVER_PLAN.md §5; TEST_STRATEGY.md §5 row 1; COMPONENT_MAPPING.md §6",
        "contract": {
            "endpoints": [
                "GET /api/performance/cycles?status=OPEN,DRAFT&sort=cycleYear,desc; POST/PUT cycles; POST /api/performance/cycles/{id}/open|close|generate-reviews (PERFORMANCE:ADMIN)",
                "GET /api/performance/cycles/{cycleId}/reviews; GET /api/performance/reviews/mine; POST /api/performance/reviews/{id}/self-assessment | manager-review | acknowledge",
                "GET/POST /api/performance/reviews/{reviewId}/goals; PATCH /api/performance/goals/{id}/progress",
                "GET /api/performance/cycles/{id}/team-reviews; GET /api/performance/cycles/{id}/rating-distribution",
            ],
            "error_codes": ["-20401", "-20402", "-20403", "-20001"],
            "extra": ["Review status machine and goal category/status domains as enums in the OpenAPI spec", "validation-schema.json entries for review/goal DTOs (rating 1.0–5.0, weight/progress 0–100)"],
        },
        "backend": {
            "scope": [
                "performance-service: ReviewCycle, PerformanceReview, PerformanceGoal entities (Flyway V2__performance.sql on the P0 baseline), ReviewCycleService, PerformanceReviewService, GoalService with the status machines of COMPONENT_MAPPING.md §6; set-based generate_reviews_for_cycle (PERF-05)",
                "Level 1: JUnit for every status transition and error code; PostgreSQL repository tests for cycle/review/goal queries; AuditService + NotificationService calls asserted",
                "Cutover data migration script for the four performance tables (Oracle extract → PostgreSQL, sequences restarted at MAX+1) and the reverse extract (CUTOVER_PLAN.md §5.3)",
            ],
            "l2_wiring": "Add performance scenarios to tools/parallel-run/: create/open/close cycle (-20401), self-assessment/manager-review/acknowledge transitions (-20402), rating bounds (-20403), goal progress 100 → COMPLETED, generate_reviews_for_cycle row counts.",
        },
        "frontend": {
            "pages": [
                "PerformancePage tabs: Review Cycles grid (+ admin actions), My Reviews (self/manager assessment editors, RATING_LABEL), Goals grid (+ Add goal, progress), manager dashboard widgets (team reviews, rating distribution) – COMPONENT_MAPPING.md §6",
                "Reuse AuthContext/ProtectedRoute/Toolbar/useErrorHandler/ReferenceDropdown from the P0 shell; consume validation-schema.json for review/goal forms",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path: admin creates + opens a cycle and generates reviews → employee submits self-assessment → manager submits review with rating → employee acknowledges → goal added and progressed to 100 shows COMPLETED (frontend ↔ backend ↔ PostgreSQL).",
            "l2": "tools/parallel-run performance scenarios vs legacy utPLSQL PKG_PERFORMANCE: zero unexplained diffs (TEST_STRATEGY.md §5 row 1).",
            "l3": "VW_PENDING_APPROVALS filtered to ITEM_TYPE='PERFORMANCE' equals the PostgreSQL equivalent after the same scripted actions on both sides; other five views untouched vs baseline.",
            "extra": "",
        },
        "approvals": [
            {
                "gate_id": "P1.bake-4-weeks",
                "title": "Four-week production bake with zero rollbacks (CUTOVER_PLAN.md §5.4)",
                "text": "Calendar gate. performance=NEW has served production for 4 weeks with no rollback and stable Level-3 reconciliation. The workflow does not compute or wait out the calendar; a human confirms.",
            }
        ],
        "post_promotion": "Flip proxy flag performance=NEW; Forms HRMS_PERFORMANCE retired; PKG_PERFORMANCE dropped on Oracle after the bake approval.",
    },
    "P2": {
        "id": "P2",
        "slug": "p2-leave",
        "title": "Phase 2 – Leave (leave-service + React leave pages)",
        "plan_sections": "CUTOVER_PLAN.md §6; TEST_STRATEGY.md §5 row 2; COMPONENT_MAPPING.md §5",
        "contract": {
            "endpoints": [
                "GET /api/leave/requests/mine; GET /api/leave/requests?empId= (LEAVE:VIEW_ALL); POST /api/leave/requests; POST /api/leave/requests/{id}/cancel|approve|reject",
                "GET /api/leave/balances/mine?year=; GET /api/leave/business-days?start&end; GET /api/leave/approvals/pending; GET /api/leave/team-calendar?from&to",
                "GET /api/reference/leave-types?active=true (already in P0 contract; confirm response fields ACCRUAL_FLAG, MIN_TENURE_DAYS, REQUIRES_APPROVAL, REQUIRES_DOCUMENT)",
                "Admin batch trigger endpoints reserved for P5 (accrual/carryover) – declare but mark deferred",
            ],
            "error_codes": ["-20001", "-20201", "-20202", "-20203", "-20204", "-20210", "-20211", "-20212"],
            "extra": [
                "Balance semantics: AVAILABLE = OPENING + ACCRUED − USED + ADJUSTMENT − PENDING (table semantics, VAL-05) is the API contract",
                "Declared, intentional differences vs legacy: BUG-04 (carryover expiry), BUG-05 (observed holidays), BUG-06 (AM+PM same day not an overlap) – list them so the Level-2 diff can whitelist exactly these",
            ],
        },
        "backend": {
            "scope": [
                "leave-service: LeaveRequest, LeaveBalance, LeaveType entities (Flyway V3__leave.sql), LeaveRequestService.submit/cancel/approve/reject reproducing the PKG_LEAVE error contract, BusinessCalendar with observed-holiday shifting, LeaveAccrualJob (accrual/carryover/expiry, set-based, idempotent via LEAVE_ACCRUAL_LOG) – COMPONENT_MAPPING.md §5",
                "Level 1: JUnit per error code and state transition; business-day and holiday tests; carryover expiry idempotence; PostgreSQL tests for the STORED AVAILABLE column and UK_LEAVE_BAL",
                "Cutover migration + reverse extract for LEAVE_* tables (CUTOVER_PLAN.md §6.3)",
            ],
            "l2_wiring": "Add leave scenarios to tools/parallel-run/: submit happy path, each -202xx/-2021x failure, cancel pending vs approved balance restoration, approve/reject, accrual/carryover/expiry on the seed year; mark BUG-04/05/06 cases as expected differences.",
        },
        "frontend": {
            "pages": [
                "LeavePage tabs: My Requests (grid + balances + Cancel Request), Submit Request (LeaveType ReferenceDropdown, dates, half-day, live business-day count and balance), Approvals, Team Calendar – COMPONENT_MAPPING.md §5",
                "Reuse shell primitives; consume validation-schema.json for LeaveRequestDto",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path: employee submits a request (sees live days/balance) → manager approves from Approvals → balance moves PENDING→USED → employee cancels an approved request → USED restored; half-day AM + PM same day both accepted.",
            "l2": "tools/parallel-run leave scenarios vs legacy utPLSQL PKG_LEAVE: only the declared BUG-04/05/06 differences appear in the diff report.",
            "l3": "VW_LEAVE_SUMMARY and VW_PENDING_APPROVALS (ITEM_TYPE='LEAVE') vs PostgreSQL equivalents; the documented AVAILABLE-vs-PENDING offset (VAL-05) is the only accepted difference until P5.",
            "extra": "",
        },
        "approvals": [
            {
                "gate_id": "P2.accrual-cycle",
                "title": "One full monthly accrual cycle run by LeaveAccrualJob with reconciled balances (CUTOVER_PLAN.md §6.4)",
                "text": "Calendar gate. The scheduler hand-over from DBMS_SCHEDULER to LeaveAccrualJob has completed at least one real month-end and the Level-3 leave reconciliation still holds.",
            }
        ],
        "post_promotion": "Flip proxy flag leave=NEW; Forms HRMS_LEAVE retired; PKG_LEAVE and TRG_LEAVE_REQUEST_AUDIT dropped after the accrual-cycle approval.",
    },
    "P3": {
        "id": "P3",
        "slug": "p3-employee",
        "title": "Phase 3 – Employee (salary-module first, then employee-service; React employee pages)",
        "plan_sections": "CUTOVER_PLAN.md §7 (esp. §7.1 ARCH-01 pre-condition); TEST_STRATEGY.md §5 row 3; COMPONENT_MAPPING.md §3",
        "contract": {
            "endpoints": [
                "GET /api/employees (paged, filters), GET /api/employees/{id}, POST /api/employees, PUT /api/employees/{id}, POST /api/employees/{id}/terminate|transfer",
                "Salary (owned by salary-module): GET /api/employees/{id}/salary, POST /api/employees/{id}/salary (change), GET /api/employees/{id}/salary/history",
                "GET /api/employees/{id}/history, /dependents, /contacts (read + write per COMPONENT_MAPPING.md §3)",
                "Reference: departments, job-titles (with GRADE_MIN/GRADE_MAX), locations, manager search (P0 contract, re-confirmed)",
            ],
            "error_codes": ["-20001", "-20002", "-20003", "-20004", "-20005", "-20010", "-20011", "-20012", "-20101", "-20104", "-20501", "-20502", "-20503", "-20504"],
            "extra": [
                "Employee number from SEQ_EMP_NUMBER (server-assigned, read-only in the API)",
                "No DELETE endpoint (-20504 semantics: terminate only); no direct reactivation (-20503)",
                "validation-schema.json entries: @Email server rule wins (VAL-02), @HireDateWithinLimit, @Ssn (masked, never echoed), phone patterns",
                "Write flows are exposed behind proxy flag employee=NEW_READONLY → NEW; the frontend must gate write UI on that flag",
            ],
        },
        # ARCH-01: the backend is two sequential nodes. Node 1 lands on its own
        # branch stacked on the contract; node 2 stacks on node 1.
        "backend_sequence": [
            {
                "name": "backend-salary",
                "scope": [
                    "salary-module: SalaryService as the sole owner of SALARY_RECORDS (exactly one ACTIVE_FLAG='Y' row per employee, close-previous-on-change, assertWithinGrade vs JOB_GRADES, compa-ratio), SalaryRecord entity + Flyway V4__salary.sql, AuditService replaces TRG_SALARY_AUDIT (COMPONENT_MAPPING.md §3.1, MODERNIZATION_BLUEPRINT.md §9 rule 1)",
                    "Level 1: JUnit for the single-active invariant, grade-band check (-20101), history ordering; PostgreSQL repository tests incl. a partial unique index on (emp_id) WHERE active_flag='Y'",
                ],
                "l2_wiring": "Add salary scenarios to tools/parallel-run/: create_salary_record, salary change closes prior row, -20101, audit rows (PKG_PAYROLL.create_salary_record / TRG_SALARY_AUDIT vs SalaryService).",
            },
            {
                "name": "backend",
                "scope": [
                    "employee-service consuming SalaryService (never touching SALARY_RECORDS directly): Employee, EmployeeHistory, Dependent, Contact entities + Flyway V5__employee.sql; EmployeeService.create/update/terminate/transfer reproducing PKG_EMPLOYEE.validate_employee and the trg_employees rules (-20501…-20504) as service invariants; manager-cycle prevention (WITH RECURSIVE); session revocation on termination via auth-service (COMPONENT_MAPPING.md §3.2)",
                    "Level 1: JUnit per error code, hire-date rule, e-mail uniqueness, no reactivation, no physical delete, manager cycle; PostgreSQL repository tests for the recursive org query and '' vs NULL normalisation",
                    "Cutover migration + reverse extract for EMPLOYEES/SALARY_RECORDS/EMPLOYEE_HISTORY/DEPENDENTS/CONTACTS (CUTOVER_PLAN.md §7.3)",
                ],
                "l2_wiring": "Add employee scenarios to tools/parallel-run/: create (number from sequence), update, terminate (-20005, session revoked), transfer (-20012), each validate_employee code, trigger-rule codes -20501…-20504.",
            },
        ],
        "frontend": {
            "pages": [
                "EmployeePage: searchable employee grid, detail form (departments/job-titles/locations/manager ReferenceDropdowns), tabs History/Salary/Dependents/Contacts, terminate/transfer dialogs, salary dialog with grade-band warning banner – COMPONENT_MAPPING.md §3",
                "Write flows (create/update/terminate/transfer/salary change) are implemented now but rendered only when the proxy reports employee=NEW; while employee=NEW_READONLY the page is read-only. This is what lets the frontend start from the contract while salary-module is still landing (CUTOVER_PLAN.md §7.1).",
                "Reuse shell primitives; consume validation-schema.json for EmployeeDto/SalaryChangeDto",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path, two stages in one run: (1) employee=NEW_READONLY – search, open detail, browse history/salary tabs; (2) employee=NEW – create employee (number from sequence, salary created through SalaryService), change salary (previous row closed), attempt duplicate e-mail (-20502), attempt manager cycle (-20004), terminate (-20005 on second attempt, session revoked).",
            "l2": "tools/parallel-run salary + employee scenarios vs legacy utPLSQL PKG_EMPLOYEE/PKG_PAYROLL.create_salary_record/trg_employees: zero unexplained diffs; SEQ_EMP_NUMBER makes -20002 unreachable (declared).",
            "l3": "VW_EMPLOYEE_DIRECTORY, VW_ORG_HIERARCHY (ORG_LEVEL/ORG_PATH/IS_LEAF), VW_EMPLOYEE_COMPENSATION vs PostgreSQL equivalents after the same scripted writes on both sides.",
            "extra": "Assert the stack order: the salary-module branch is fully merged into the employee branch and SalaryService is the only code path writing salary_records (static check: no JPA repository on SalaryRecord outside the salary package).",
        },
        "approvals": [
            {
                "gate_id": "P3.readonly-then-write-bake",
                "title": "employee=NEW_READONLY reconciled in production, then one week of employee=NEW with zero rollbacks (CUTOVER_PLAN.md §7.3–§7.4)",
                "text": "Calendar gate. Confirms the two-step flag promotion has happened in production and TRG_EMP_* / PKG_EMPLOYEE can be dropped on Oracle.",
            }
        ],
        "post_promotion": "Proxy flag employee=NEW_READONLY then NEW; Forms HRMS_EMPLOYEE retired; TRG_EMP_*, TRG_SALARY_AUDIT and PKG_EMPLOYEE dropped after approval.",
    },
    "P4": {
        "id": "P4",
        "slug": "p4-payroll",
        "title": "Phase 4 – Payroll (pure-Java TaxEngine/PayrollRunService; shadow mode is the gate)",
        "plan_sections": "CUTOVER_PLAN.md §8 (§8.2–§8.4 shadow mode); TEST_STRATEGY.md §2.2 shadow mode, §5 row 4; COMPONENT_MAPPING.md §4; MODERNIZATION_BLUEPRINT.md §4.3",
        "contract": {
            "endpoints": [
                "GET /api/payroll/periods?status=OPEN&sort=periodStartDate,desc; POST /api/payroll/periods/{periodId}/close",
                "GET /api/payroll/periods/{periodId}/runs; POST /api/payroll/periods/{periodId}/runs {runType}; POST /api/payroll/runs/{runId}/calculate (202 + GET /api/payroll/runs/{runId}/status); POST /api/payroll/runs/{runId}/approve|reverse",
                "GET /api/payroll/runs/{runId}/details; GET /api/payroll/runs/{runId}/payslips/{empId} (YTD from approved runs); GET /api/payroll/runs/{runId}/register.csv (streamed, masked bank data)",
                "Shadow: GET /api/payroll/shadow/runs/{runId}/diff (PayrollShadowRunner report, internal/admin)",
            ],
            "error_codes": ["-20101", "-20102", "-20103", "-20104", "-20001"],
            "extra": [
                "Tax element IDs 100 FED_TAX, 101 STATE_TAX, 102 FICA, 103 MEDICARE as named constants validated against PAY_ELEMENTS at startup",
                "Sign convention (earnings +, taxes/deductions −) and PAYROLL_DETAILS.STATUS='ERROR' rows as the failure contract (VW_PAYROLL_LATEST depends on it)",
                "TAX_BRACKETS table contract (year, filing status, state, allowance, standard deduction) replacing the hard-coded 2024 brackets (BUG-02) – seeded so that 2024 inputs reproduce legacy to the cent",
                "Proxy flags payroll=LEGACY|NEW and payroll.engine=LEGACY|JAVA; the React payroll pages render only when payroll=NEW",
            ],
        },
        "backend": {
            "scope": [
                "payroll-service: PayPeriod, PayrollRun, PayrollDetail entities + Flyway V6__payroll.sql, TAX_BRACKETS + seed; TaxEngine (reads TAX_BRACKETS; 2024 rows reproduce legacy incl. the ELSE 0.05 state default as an explicit row), PayrollRunService.createRun/calculate (Spring Batch chunked, restartable, per-employee ERROR rows)/approve/reverse, PayslipService (YTD from approved runs), PayRegisterExporter (object storage / HTTP download) – COMPONENT_MAPPING.md §4",
                "PayrollShadowRunner: for every legacy run on Oracle, run the Java engine on PostgreSQL against the same inputs, write to shadow tables, diff per (RUN, EMP_ID, ELEMENT_ID) to the cent, publish a report (CUTOVER_PLAN.md §8.2 item 4.4, §8.3)",
                "Level 1: golden-file TaxEngine tests for every bracket edge, filing status and state on 2024 rules; run status machine and -2010x codes; sign convention; PostgreSQL repository tests for run/detail aggregates",
            ],
            "l2_wiring": "Add payroll scenarios to tools/parallel-run/: create_payroll_run (-20102), calculate on the seed period (every employee, every element, to the cent), approve (-20103), no active salary (-20104), payslip YTD; PayrollShadowRunner reuses the same diff engine.",
        },
        "frontend": {
            "pages": [
                "PayrollPage tabs: Pay Periods grid, Payroll Runs grid with Create Run / Calculate (progress + error-count badge) / Approve (hidden without PAYROLL:APPROVE), Pay Details grid, payslip view, register download – COMPONENT_MAPPING.md §4",
                "Pages are built and tested now but are reachable only when the proxy reports payroll=NEW, which happens after the shadow gate approval; until then the tile routes to Forms",
                "Reuse shell primitives; consume validation-schema.json",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path with payroll=NEW in the test environment only: HR opens period → Create Run → Calculate (async progress) → Pay Details show FED/STATE/FICA/MEDICARE with correct signs → Approve → payslip YTD → register.csv downloads with masked bank data.",
            "l2": "PayrollShadowRunner shadow-mode comparison IS the Level-2 gate here: legacy PKG_PAYROLL.calculate_payroll on Oracle vs Java engine on PostgreSQL for every seed/fixture period, diff per (RUN, EMP_ID, ELEMENT_ID) must be 0.00 for 2024-rule inputs; any non-zero diff is a failure with the offending element listed (CUTOVER_PLAN.md §8.2–§8.4, TEST_STRATEGY.md §2.2).",
            "l3": "VW_PAYROLL_LATEST vs PostgreSQL equivalent after identical runs; VW_EMPLOYEE_COMPENSATION unchanged.",
            "extra": "Shadow report published as an artifact with per-run totals; PayrollShadowRunner must be schedulable against production runs (this is what the manual shadow gate will watch).",
        },
        "approvals": [
            {
                "gate_id": "P4.shadow-gate",
                "title": "Shadow gate – ≥3 consecutive real production periods with 0.00 diff; promote payroll.engine=JAVA and payroll=NEW together (CUTOVER_PLAN.md §8.3)",
                "text": "Calendar gate. PayrollShadowRunner has compared at least three consecutive real production periods with zero-cent difference on 2024-rule inputs and every other difference is explained and signed off by the payroll owner. Only after this approval are payroll.engine=JAVA and payroll=NEW promoted and the React payroll pages go live.",
            },
            {
                "gate_id": "P4.rollback-window-closed",
                "title": "Three production periods on the Java engine with no rollback (CUTOVER_PLAN.md §8.4)",
                "text": "Calendar gate. After promotion, three further production periods have run on payroll.engine=JAVA with no rollback; PKG_PAYROLL may then be dropped on Oracle and P5 may start.",
            },
        ],
        "post_promotion": "Promote payroll.engine=JAVA and payroll=NEW together (only after P4.shadow-gate); Forms HRMS_PAYROLL retired; PKG_PAYROLL dropped after P4.rollback-window-closed.",
    },
    "P5": {
        "id": "P5",
        "slug": "p5-reporting-decommission",
        "title": "Phase 5 – Reporting/Integration, reference-data ownership, Forms + Oracle decommission",
        "plan_sections": "CUTOVER_PLAN.md §9; TEST_STRATEGY.md §5 row 5; COMPONENT_MAPPING.md §9 (PKG_REPORTING, PKG_INTEGRATION); MODERNIZATION_BLUEPRINT.md §8 Reporting row",
        "contract": {
            "endpoints": [
                "reporting-service (read-only): GET /api/reports/employee-directory, /org-hierarchy, /employee-compensation, /leave-summary, /payroll-latest, /pending-approvals (JSON + .csv), mirroring the six VW_* views and PKG_REPORTING ref cursors",
                "Admin UI endpoints: reference-data CRUD (departments, job-titles, job-grades, locations, leave-types, system parameters), POST /api/admin/leave/accrual|carryover run triggers, audit-log search",
                "integration-service: GL feed, benefits feed, time-attendance import (BUG-08 – contract explicitly marks the unspecified fields) writing to object storage / HTTP download instead of UTL_FILE",
            ],
            "error_codes": ["-20001", "-20003", "-20011 (reference-data validity)", "any new admin codes must be added to COMPONENT_MAPPING.md §11 in the contract PR"],
            "extra": [
                "Reference-data ownership transfers to PostgreSQL in this phase; declare the cutover of CDC direction and its shutdown",
                "VAL-05 fix: PostgreSQL leave-summary report subtracts PENDING; declare the diff vs Oracle VW_LEAVE_SUMMARY as the accepted final difference",
            ],
        },
        "backend": {
            "scope": [
                "reporting-service: ReportingController/ReportingService with pure JPA/SQL queries on PostgreSQL (WITH RECURSIVE org chart), JSON/CSV endpoints – no JDBC bridge to PKG_REPORTING",
                "integration-service: GL/benefits feed writers, time-attendance import, object-storage output (SEC-11, ARCH-05, PROC-02)",
                "Admin/reference-data services (owner of the reference tables on PostgreSQL), accrual/carryover admin triggers; CDC shutdown + final Oracle extract scripts; Forms/WebLogic/Oracle decommission runbook (CUTOVER_PLAN.md §9)",
                "Level 1: JUnit for every report query against a Testcontainers seed; feed layout golden files; reference-data validation codes",
            ],
            "l2_wiring": "Add reporting scenarios to tools/parallel-run/: each PKG_REPORTING ref cursor vs the reporting endpoint row-for-row on the frozen seed; feed files vs legacy UTL_FILE output captured on Oracle.",
        },
        "frontend": {
            "pages": [
                "Reports pages (six reports with filters, CSV export), Admin pages (reference-data CRUD, system parameters, accrual/carryover run buttons, audit-log search) – the missing HRMS_ADMIN form is rebuilt from requirements",
                "Remove all legacy tiles and the SSO bridge entry points from the app shell once every module flag is NEW (behind a decommission flag until the 30-day gate)",
            ],
        },
        "integration": {
            "e2e": "Playwright golden path: each report renders and exports CSV; admin adds a job title and it appears in the employee ReferenceDropdown; admin runs accrual and LEAVE_ACCRUAL_LOG shows the run; no legacy tile is visible when the decommission flag is on.",
            "l2": "tools/parallel-run reporting scenarios vs PKG_REPORTING ref cursors: row-for-row identical on the frozen seed; feed files byte-compare with the Oracle capture except declared header/encoding differences.",
            "l3": "Final six-view reconciliation, all views, on the final Oracle extract vs PostgreSQL; only the declared VAL-05 leave-summary difference remains (TEST_STRATEGY.md §5 row 5).",
            "extra": "Static gate: no PL/SQL, PL/pgSQL, trigger or .pll in the target repo (grep + Flyway scan); no Oracle JDBC driver on the backend runtime classpath.",
        },
        "approvals": [
            {
                "gate_id": "P5.zero-hit-30-days",
                "title": "30 days with zero legacy proxy hits (CUTOVER_PLAN.md §9.3)",
                "text": "Calendar gate. The reverse proxy has recorded zero /legacy/* hits for 30 consecutive days with every module flag at NEW. Only after this approval are Forms, WebLogic and the legacy Oracle instance decommissioned.",
            }
        ],
        "post_promotion": "reporting=NEW; CDC stopped; after P5.zero-hit-30-days: Forms/WebLogic/Oracle decommissioned, SSO bridge removed, final Oracle extract archived.",
    },
}

PHASE_ORDER = ["P0", "P1", "P2", "P3", "P4", "P5"]


# --------------------------------------------------------------------------
# Structured-output schemas
# --------------------------------------------------------------------------

BRANCH_RESULT = {
    "type": "object",
    "properties": {
        "branch": {"type": "string", "description": "Branch that was pushed"},
        "pr_url": {"type": "string", "description": "PR URL (empty if none)"},
        "head_sha": {"type": "string"},
        "level1_passed": {"type": "boolean", "description": "Level-1 tests green on this branch"},
        "summary": {"type": "string"},
        "blockers": {"type": "string", "description": "Empty when none"},
    },
    "required": ["branch", "pr_url", "head_sha", "level1_passed", "summary", "blockers"],
}

CONTRACT_RESULT = {
    "type": "object",
    "properties": {
        "branch": {"type": "string"},
        "pr_url": {"type": "string"},
        "head_sha": {"type": "string"},
        "openapi_path": {"type": "string"},
        "error_codes_path": {"type": "string"},
        "validation_schema_path": {"type": "string"},
        "summary": {"type": "string"},
        "blockers": {"type": "string"},
    },
    "required": ["branch", "pr_url", "head_sha", "openapi_path", "error_codes_path", "validation_schema_path", "summary", "blockers"],
}

MERGE_RESULT = {
    "type": "object",
    "properties": {
        "integration_branch": {"type": "string"},
        "head_sha": {"type": "string"},
        "pr_url": {"type": "string"},
        "conflicts_resolved": {"type": "string", "description": "Empty when the merge was clean"},
    },
    "required": ["integration_branch", "head_sha", "pr_url", "conflicts_resolved"],
}

INTEGRATION_RESULT = {
    "type": "object",
    "properties": {
        "level1_passed": {"type": "boolean"},
        "level2_passed": {"type": "boolean"},
        "level3_passed": {"type": "boolean"},
        "e2e_passed": {"type": "boolean"},
        "phase_specific_passed": {"type": "boolean", "description": "The 'extra' checks for this phase"},
        "failure_owner": {
            "type": "string",
            "enum": ["none", "backend", "frontend", "both", "contract", "environment"],
            "description": "Where remediation must go. 'contract' = the frozen API surface itself is wrong; 'environment' = harness/infra, not the code under test",
        },
        "findings": {"type": "string", "description": "Actionable list, one line per failing scenario/query, prefixed [backend]/[frontend]/[contract]/[env]"},
        "report_url": {"type": "string", "description": "Where the full reports (Playwright, parallel-run diff, reconciliation) were published"},
        "head_sha": {"type": "string", "description": "Integration branch SHA that was tested"},
    },
    "required": ["level1_passed", "level2_passed", "level3_passed", "e2e_passed", "phase_specific_passed", "failure_owner", "findings", "report_url", "head_sha"],
}

APPROVAL_RESULT = {
    "type": "object",
    "properties": {
        "approved": {"type": "boolean"},
        "approver": {"type": "string"},
        "notes": {"type": "string"},
    },
    "required": ["approved", "approver", "notes"],
}

PROMOTE_RESULT = {
    "type": "object",
    "properties": {
        "phase_branch": {"type": "string"},
        "head_sha": {"type": "string"},
        "prs_marked_ready": {"type": "string"},
        "summary": {"type": "string"},
    },
    "required": ["phase_branch", "head_sha", "prs_marked_ready", "summary"],
}


# --------------------------------------------------------------------------
# Branch layout (stacked PRs)
# --------------------------------------------------------------------------

def phase_branch(phase):
    return f"phase/{phase['slug']}"


def sub_branch(phase, name):
    # `phase/<slug>` and `<slug>/<name>` are distinct ref namespaces, so the
    # phase branch and its sub-branches can coexist.
    return f"{phase['slug']}/{name}"


def previous_phase_branch(phase_id):
    idx = PHASE_ORDER.index(phase_id)
    return BASE_BRANCH if idx == 0 else phase_branch(PHASES[PHASE_ORDER[idx - 1]])


def bullets(items):
    return "\n".join(f"- {i}" for i in items)


# --------------------------------------------------------------------------
# Prompts
# --------------------------------------------------------------------------

def contract_prompt(phase):
    c = phase["contract"]
    return f"""
You are the CONTRACT node for {phase['title']}.
{GLOBAL_RULES}

Read {phase['plan_sections']}.

Branching: `git fetch`; create `{sub_branch(phase, 'contract')}` from `{previous_phase_branch(phase['id'])}`; if `{phase_branch(phase)}` does not exist yet, create it from the same commit and push it (it is the PR base for this phase).

Freeze the API surface BEFORE any implementation exists. Deliver on the contract branch:
1. `contracts/{phase['slug']}/openapi.yaml` – every endpoint below with request/response DTOs, auth requirements (@PreAuthorize authorities), status codes, and the ApiError response for each documented failure:
{bullets(c['endpoints'])}
2. `contracts/{phase['slug']}/error-codes.md` – the ApiError.code values this module may return, copied from COMPONENT_MAPPING.md §11 with HTTP status and triggering rule:
{bullets(c['error_codes'])}
3. `frontend/src/generated/validation-schema.json` for this module's DTOs, produced by the hrms-validation exporter (TEST_STRATEGY.md §2.1). If the exporter does not exist yet (P0), define its output format and commit a hand-written first version plus the snapshot test that will guard it.
4. Additional contract items:
{bullets(c['extra'])}
5. `contracts/{phase['slug']}/README.md` – one paragraph: what backend and frontend may assume, what is explicitly out of contract, and which legacy behaviours are intentionally NOT reproduced.

Do NOT implement services, entities, pages or migrations. Do not change COMPONENT_MAPPING.md §11 except to add codes that are missing (and say so).
Open a PR from `{sub_branch(phase, 'contract')}` into `{phase_branch(phase)}` titled `[{phase['id']}] contract: {phase['slug']}` using the repository PR template. Push and report.
""".strip()


def impl_prompt(phase, role, spec, branch, base, extra_note=""):
    if role == "backend":
        body = f"""
Implement on `{branch}` (create from `{base}`, PR into `{base}`):
{bullets(spec['scope'])}

Level 2 wiring (TEST_STRATEGY.md §2.2): {spec['l2_wiring']}
Every scenario is registered in tools/parallel-run/ so the integration session can run it unchanged; do NOT run the parallel run against Oracle yourself unless credentials are already provisioned – Level 2 is the integration session's job.

Definition of done: the contract's OpenAPI is implemented exactly (generate/compare, no undocumented endpoints); ApiError codes match error-codes.md; Level-1 tests (JUnit 5 + Testcontainers PostgreSQL) green via the repo build command; Flyway migrations apply on the P0 baseline; lint/format pass; PR open with the template; `level1_passed=true` only if the suite really ran green.
"""
    else:
        body = f"""
Implement on `{branch}` (create from `{base}`, PR into `{base}`):
{bullets(spec['pages'])}

Rules: every API call goes through the contract's OpenAPI (generate a typed client from contracts/{phase['slug']}/openapi.yaml or hand-write it 1:1); form validation comes from frontend/src/generated/validation-schema.json – never duplicate a rule; server ApiError.code values from error-codes.md are mapped in useErrorHandler; Vitest + React Testing Library component tests for every page/dialog and the validation-schema snapshot test; Playwright specs for the golden path live in frontend/e2e/ but are only smoke-run against a mocked/msw backend here – the real end-to-end run is the integration session's job.

Definition of done: pages render against msw mocks of the contract; Vitest green; lint/typecheck green; PR open with the template; `level1_passed=true` means Vitest + typecheck green.
"""
    return f"""
You are the {role.upper()} implementation node for {phase['title']}.
{GLOBAL_RULES}

Read {phase['plan_sections']} and the frozen contract under contracts/{phase['slug']}/ on branch `{sub_branch(phase, 'contract')}`. The contract is frozen: if you believe it is wrong, implement what it says and report the problem in `blockers` – do not change it.
{extra_note}
{body}
Report `blockers` non-empty if anything in the definition of done is not met.
""".strip()


def remediation_prompt(phase, role, branch, findings, report_url, round_no):
    return f"""
You are the {role.upper()} REMEDIATION node for {phase['title']} (remediation round {round_no} of {MAX_REMEDIATION_ROUNDS}).
{GLOBAL_RULES}

The integration session on `{sub_branch(phase, 'integration')}` failed and routed these findings to {role}. Full reports: {report_url}
Findings:
{findings}

Work on the existing branch `{branch}` (fetch it; do not rebase or force-push it – the fan-in step re-merges). Fix only the [{role}] items (and [both] items on your side). Keep the frozen contract; if a finding can only be fixed by changing the contract, stop and report it in `blockers` with `[contract]` prefix.
Add/adjust Level-1 tests that reproduce each finding before fixing it. Run the Level-1 suite, lint/typecheck; push; update the existing PR description with a "Remediation round {round_no}" section.
""".strip()


def merge_prompt(phase, heads, round_no):
    heads_txt = bullets(f"{name}: {sha}" for name, sha in heads)
    return f"""
You are the FAN-IN node for {phase['title']} (round {round_no}).
{GLOBAL_RULES}

Create or refresh the phase-integration merge branch `{sub_branch(phase, 'integration')}`:
1. `git fetch`; reset the integration branch to `{sub_branch(phase, 'contract')}` (create it if missing; force-push is allowed ONLY on this integration branch).
2. Merge, in order, these branches at exactly these SHAs (fail if a SHA is not on the branch):
{heads_txt}
3. Resolve clerical conflicts only (imports, lockfiles, generated client, adjacent edits). A substantive conflict (same function changed on both sides with different intent) is a failure: describe it in `conflicts_resolved` prefixed `UNRESOLVED:` and stop.
4. Run the full repo build (backend + frontend compile, Level-1 suites) on the merged tree. If it fails, describe the failure in `conflicts_resolved` prefixed `BUILD-FAIL:` and stop.
5. Push; open (or refresh) the PR `{sub_branch(phase, 'integration')}` → `{phase_branch(phase)}` titled `[{phase['id']}] integration-merge: {phase['slug']}` marked as draft.
""".strip()


def integration_prompt(phase, merge, round_no):
    it = phase["integration"]
    return f"""
You are the INTEGRATION-TEST session for {phase['title']} (round {round_no}). You run in your own session; do not modify application code – you produce a verdict and a routed findings list.
{GLOBAL_RULES}

Check out `{sub_branch(phase, 'integration')}` at {merge['head_sha']}. Start PostgreSQL + backend + frontend (docker compose / repo scripts). Oracle golden-oracle credentials, if provisioned, are in the environment; if Level 2/3 cannot run for lack of an Oracle connection, set failure_owner=environment and say exactly what is missing.

Run all of the following and record each as pass/fail:
- Level 1 (TEST_STRATEGY.md §2.1): full backend JUnit + frontend Vitest on the merged tree.
- Playwright golden-path e2e (frontend ↔ backend ↔ PostgreSQL, real stack, no mocks): {it['e2e']}
- Level 2 (TEST_STRATEGY.md §2.2, tools/parallel-run/): {it['l2']}
- Level 3 (TEST_STRATEGY.md §2.3, tools/reconcile/ + tests/reconciliation/pg/): {it['l3']}
- Phase-specific: {it['extra'] or 'none'}

Publish the reports (Playwright HTML, parallel-run diff, reconciliation diff) as artifacts or a PR comment on the integration PR and put the link in report_url.

Routing rule for `failure_owner` (TEST_STRATEGY.md §5 gate; CUTOVER_PLAN.md rollback rules):
- Level-2 diff or Level-3 mismatch or a backend Level-1 failure → backend.
- Playwright failure where the API responded per contract but the UI misbehaved, a Vitest failure, or a validation-schema mismatch in the UI → frontend.
- Both kinds present → both. Each finding line MUST be prefixed [backend], [frontend], [contract] or [env].
- The frozen OpenAPI/error-code contract itself is wrong → contract (this halts the workflow for a human).
- Harness/infra only → environment.
Set failure_owner=none only when every check passed.
""".strip()


def approval_prompt(phase, gate):
    return f"""
You are a MANUAL-APPROVAL node for {phase['title']}: gate `{gate['gate_id']}` – {gate['title']}.

This gate is a human decision that the workflow must never auto-advance. {gate['text']}

Do not write or run code. Using message_user with block_on_user=true, ask the approver to confirm whether the gate criteria are met, quoting the criteria above and asking for their name/role and any evidence link. Wait for the answer. If they decline, are unsure, or say the calendar period has not elapsed, report approved=false with their notes; the workflow will stop here and can be resumed later with the same run_id once the gate is granted (see WORKFLOW_README.md "Resuming after a calendar gate").
""".strip()


def promote_prompt(phase, integration_head, approvals):
    appr = bullets(f"{a['gate_id']}: {a['approver']} – {a['notes']}" for a in approvals) or "- none required"
    return f"""
You are the PROMOTE node for {phase['title']}.
{GLOBAL_RULES}

The phase gate passed (Level 1 + 2 + 3 + Playwright) on `{sub_branch(phase, 'integration')}` at {integration_head}, and these manual approvals were granted:
{appr}

Do:
1. Fast-forward `{phase_branch(phase)}` to {integration_head} (fetch; it must be a fast-forward – if not, stop and report). Push.
2. Mark the contract, backend, frontend and integration-merge PRs of this phase ready for review and add a comment linking the integration report; do not merge them into `{previous_phase_branch(phase['id'])}` – the stack is merged bottom-up by humans.
3. Append a row to CUTOVER_PLAN.md §10 status (or create `cutover-log/{phase['slug']}.md`) recording gate results, approvals, and the post-promotion actions that are now unblocked: {phase['post_promotion']}
Do NOT flip production proxy flags or drop anything on Oracle – those are operational steps performed by humans after this record exists.
""".strip()


# --------------------------------------------------------------------------
# Reusable per-phase sub-graph
# --------------------------------------------------------------------------

def gate_passed(result):
    return all(
        result[k]
        for k in ("level1_passed", "level2_passed", "level3_passed", "e2e_passed", "phase_specific_passed")
    ) and result["failure_owner"] == "none"


WF_PHASES = [
    {"title": "contract", "detail": "Freeze the module API surface (endpoints, ApiError codes, validation-schema.json) as a contract PR"},
    {"title": "backend", "detail": "Spring services/entities/Flyway + Level-1 JUnit + parallel-run scenarios, stacked on contract"},
    {"title": "frontend", "detail": "React pages + Vitest, consuming the generated validation schema, stacked on contract"},
    {"title": "fan-in", "detail": "Merge backend+frontend heads on top of contract into the integration branch"},
    {"title": "integration", "detail": "Playwright e2e + Level-2 parallel run + Level-3 reconciliation on the merged tree"},
    {"title": "remediate", "detail": "Fix routed findings on the owning branch, then rebase + re-run"},
    {"title": "approval", "detail": "Manual gates: calendar bakes, shadow gate, sign-offs"},
    {"title": "promote", "detail": "Fast-forward the phase branch; next phase cuts from it"},
]


async def run_agent(label, prompt, schema, mode=None, minutes=60):
    # label is "<pid>.<node>[.rN]"; the workflow phase group is the node name.
    node = label.split(".")[1]
    group = {"backend-salary": "backend", "remediate-backend": "remediate",
             "remediate-frontend": "remediate"}.get(node, node)
    return await agent(
        prompt,
        phase=group,
        schema=schema,
        label=label,
        repos=[REPO],
        mode=mode,
        soft_time_limit_minutes=min(minutes, 60),
    )


async def run_backend(phase, contract_branch, pid):
    """Backend node. P3 is two sequential nodes (salary-module → employee-service)."""
    seq = phase.get("backend_sequence")
    if not seq:
        branch = sub_branch(phase, "backend")
        return [("backend", await run_agent(
            f"{pid}.backend", impl_prompt(phase, "backend", phase["backend"], branch, contract_branch), BRANCH_RESULT, minutes=90))]

    results = []
    base = contract_branch
    for i, node in enumerate(seq, 1):
        branch = sub_branch(phase, node["name"])
        note = (
            f"\nARCH-01 sequencing (CUTOVER_PLAN.md §7.1): this is backend node {i} of {len(seq)} ('{node['name']}'). "
            + ("It must land before employee-service starts; SalaryService is the only owner of SALARY_RECORDS." if i == 1
               else f"It stacks on `{base}` (already landed) and must consume SalaryService instead of touching SALARY_RECORDS.")
        )
        res = await run_agent(
            f"{pid}.{node['name']}", impl_prompt(phase, "backend", node, branch, base, extra_note=note), BRANCH_RESULT, minutes=90)
        results.append((node["name"], res))
        if res["blockers"]:
            raise RuntimeError(f"{pid} {node['name']} reported blockers: {res['blockers']}")
        base = branch  # next node stacks on this one
    return results


async def run_phase(phase):
    pid = phase["id"]
    log(f"[{pid}] ==== {phase['title']} ====")

    # 1. Contract node – freezes the API surface; nothing fans out before it lands.
    contract = await run_agent(f"{pid}.contract", contract_prompt(phase), CONTRACT_RESULT, minutes=45)
    if contract["blockers"]:
        raise RuntimeError(f"{pid} contract blocked: {contract['blockers']}")
    contract_branch = contract["branch"]
    log(f"[{pid}] contract landed: {contract['pr_url']} @ {contract['head_sha']}")

    # 2. Fan-out – backend and frontend in their own sessions, both stacked on contract.
    frontend_branch = sub_branch(phase, "frontend")
    fe_note = ""
    if phase.get("backend_sequence"):
        fe_note = ("\nP3 deviation: you start from the contract in parallel with salary-module. Build the write flows "
                   "but render them only when the proxy flag is employee=NEW; the integration session enables them "
                   "only after salary-module and employee-service have both landed.")
    if pid == "P4":
        fe_note = ("\nP4 deviation: the React payroll pages go live only when payroll.engine is promoted to JAVA after "
                   "the manual shadow gate (≥3 periods, 0.00 diff). Build and test them fully, gated on payroll=NEW.")

    backend_results, frontend = await parallel([
        lambda: run_backend(phase, contract_branch, pid),
        lambda: run_agent(f"{pid}.frontend",
                          impl_prompt(phase, "frontend", phase["frontend"], frontend_branch, contract_branch, extra_note=fe_note),
                          BRANCH_RESULT, minutes=90),
    ])
    if frontend["blockers"]:
        raise RuntimeError(f"{pid} frontend reported blockers: {frontend['blockers']}")
    for name, r in backend_results:
        if r["blockers"]:
            raise RuntimeError(f"{pid} {name} reported blockers: {r['blockers']}")
    if not (frontend["level1_passed"] and all(r["level1_passed"] for _, r in backend_results)):
        raise RuntimeError(f"{pid}: a parallel session finished without green Level-1 tests; fan-in refused")
    log(f"[{pid}] fan-out complete: backend={[(n, r['head_sha']) for n, r in backend_results]} frontend={frontend['head_sha']}")

    # 3–6. Fan-in → integration session → gate, with bounded remediation routing.
    heads = [(r["branch"], r["head_sha"]) for _, r in backend_results] + [(frontend["branch"], frontend["head_sha"])]
    backend_branch = backend_results[-1][1]["branch"]  # top of the backend stack (P3: employee-service)

    for round_no in range(1, MAX_REMEDIATION_ROUNDS + 2):
        merge = await run_agent(f"{pid}.fan-in.r{round_no}", merge_prompt(phase, heads, round_no), MERGE_RESULT, mode="lite", minutes=30)
        if merge["conflicts_resolved"].startswith(("UNRESOLVED:", "BUILD-FAIL:")):
            raise RuntimeError(f"{pid} fan-in failed: {merge['conflicts_resolved']}")

        report = await run_agent(f"{pid}.integration.r{round_no}", integration_prompt(phase, merge, round_no), INTEGRATION_RESULT, minutes=120)
        log(f"[{pid}] integration r{round_no}: L1={report['level1_passed']} L2={report['level2_passed']} "
            f"L3={report['level3_passed']} e2e={report['e2e_passed']} owner={report['failure_owner']} {report['report_url']}")

        if gate_passed(report):
            break

        owner = report["failure_owner"]
        if owner in ("contract", "environment"):
            raise RuntimeError(f"{pid} integration failed with owner={owner}; human intervention required: {report['findings']}")
        if round_no > MAX_REMEDIATION_ROUNDS:
            raise RuntimeError(f"{pid} gate still failing after {MAX_REMEDIATION_ROUNDS} remediation rounds: {report['report_url']}")

        # Route remediation to the responsible node(s) only, then rebase the merge and re-run.
        tasks = []
        if owner in ("backend", "both"):
            tasks.append(("backend", lambda: run_agent(f"{pid}.remediate-backend.r{round_no}",
                                                       remediation_prompt(phase, "backend", backend_branch, report["findings"], report["report_url"], round_no),
                                                       BRANCH_RESULT, minutes=90)))
        if owner in ("frontend", "both"):
            tasks.append(("frontend", lambda: run_agent(f"{pid}.remediate-frontend.r{round_no}",
                                                        remediation_prompt(phase, "frontend", frontend_branch, report["findings"], report["report_url"], round_no),
                                                        BRANCH_RESULT, minutes=90)))
        fixed = await parallel([t for _, t in tasks])
        for (role, _), res in zip(tasks, fixed):
            if res["blockers"]:
                raise RuntimeError(f"{pid} {role} remediation blocked: {res['blockers']}")
            heads = [(b, res["head_sha"] if b == res["branch"] else s) for b, s in heads]
        log(f"[{pid}] remediation r{round_no} pushed by {[r for r, _ in tasks]}; re-running fan-in + integration")

    # 7. Manual approvals (calendar bakes, shadow gate, sign-offs) – never auto-advanced.
    granted = []
    for gate in phase["approvals"]:
        if gate["gate_id"] in APPROVED_GATES:
            log(f"[{pid}] gate {gate['gate_id']} pre-approved via HRMS_WF_APPROVED_GATES")
            granted.append({"gate_id": gate["gate_id"], "approver": "pre-approved (HRMS_WF_APPROVED_GATES)", "notes": ""})
            continue
        decision = await run_agent(f"{pid}.approval.{gate['gate_id']}", approval_prompt(phase, gate), APPROVAL_RESULT, mode="lite", minutes=60)
        if not decision["approved"]:
            log(f"[{pid}] STOP: gate {gate['gate_id']} not approved ({decision['approver']}: {decision['notes']}). "
                f"Re-run with the same run_id and HRMS_WF_APPROVED_GATES including {gate['gate_id']} once granted.")
            return {"phase": pid, "status": "waiting-approval", "gate": gate["gate_id"], "integration_report": report["report_url"]}
        granted.append({"gate_id": gate["gate_id"], "approver": decision["approver"], "notes": decision["notes"]})

    # 8. Promote – fast-forward the phase branch; next phase's contract cuts from it.
    promoted = await run_agent(f"{pid}.promote", promote_prompt(phase, report["head_sha"], granted), PROMOTE_RESULT, mode="lite", minutes=30)
    log(f"[{pid}] promoted {promoted['phase_branch']} @ {promoted['head_sha']}")
    return {"phase": pid, "status": "promoted", "phase_branch": promoted["phase_branch"], "head_sha": promoted["head_sha"],
            "integration_report": report["report_url"]}


# --------------------------------------------------------------------------
# Entry point – phases are strictly sequential: P0 → P1 → P2 → P3 → P4 → P5
# --------------------------------------------------------------------------

async def main():
    await register_workflow({
        "name": "hrms-phase-workflow",
        "description": "HRMS modernization: contract → parallel backend+frontend → fan-in → integration session → gate, per phase P0–P5",
        "product": f"{REPO} (Spring Boot + React on PostgreSQL)",
        "soft_time_limit_minutes": 60,
        "phases": WF_PHASES,
    })
    unknown = [p for p in PHASES_TO_RUN if p not in PHASES]
    if unknown:
        raise ValueError(f"Unknown phases in HRMS_WF_PHASES: {unknown}")
    ordered = [p for p in PHASE_ORDER if p in PHASES_TO_RUN]
    log(f"Base branch {BASE_BRANCH}; phases {ordered}; pre-approved gates {sorted(APPROVED_GATES) or 'none'}")

    outcomes = []
    for pid in ordered:
        outcome = await run_phase(PHASES[pid])
        outcomes.append(outcome)
        if outcome["status"] != "promoted":
            log(f"Workflow paused after {pid}: {json.dumps(outcome)}")
            break
    log("Outcomes:\n" + json.dumps(outcomes, indent=2))


asyncio.run(main())
