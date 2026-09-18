# HRMS Modernization Blueprint

Scope: the Oracle Forms 12c / PL/SQL HRMS application in this repository (HRMS schema, Oracle 19c, WebLogic 12c, ~200 concurrent users in 3 offices).

Companion documents: [COMPONENT_MAPPING.md](COMPONENT_MAPPING.md), [CUTOVER_PLAN.md](CUTOVER_PLAN.md), [RISK_REGISTER.md](RISK_REGISTER.md), [TEST_STRATEGY.md](TEST_STRATEGY.md).

> Evidence note. The README describes 18 forms, 12 packages, 42 tables, 15 views, 200+ triggers and 8 reports. The repository contains 6 form XML exports (`HRMS_LOGIN`, `HRMS_MENU`, `HRMS_EMPLOYEE`, `HRMS_PAYROLL`, `HRMS_LEAVE`, `HRMS_PERFORMANCE`), 2 PLL libraries, 1 menu module, 11 packages (spec + body), 2 trigger scripts, 6 views and ~40 tables. `HRMS_REPORTS` and `HRMS_ADMIN` are referenced by the menu but not checked in. Everything below is grounded in the checked-in code; statements about uncommitted modules are marked *inferred*.

---

## 1. Current-state summary

| Layer | What exists | Key observations from source |
|---|---|---|
| UI | Forms 12c modules with tab canvases, LOVs, record groups, master-detail relations, `:GLOBAL` variables for session state | Business logic lives in `WHEN-BUTTON-PRESSED`, `WHEN-VALIDATE-ITEM`, `PRE-INSERT`, `POST-QUERY` triggers **and** in packages **and** in DB triggers (3 copies). Client-side `HRMS_VALIDATION_LIB` has drifted from `PKG_VALIDATION` (email regex, salary-range caching comment/code mismatch). |
| Business logic | 11 packages, ~4.5k lines of package body | `PKG_EMPLOYEE` <-> `PKG_PAYROLL` circular dependency; `PKG_EMPLOYEE.search_employees` builds SQL by string concatenation (injection); `generate_emp_number` uses `MAX()+1` (race); payroll tax brackets/rates hard-coded for 2024; `calculate_payroll` commits every 50 employees (non-atomic); leave carryover expiry double-subtracts if run twice; observed holidays not handled. |
| Security | `PKG_SECURITY` | `authenticate` **does not verify the password at all** (comment says credentials live in a `USER_CREDENTIALS` table that is not in the repo); MD5 `hash_password`; AES key hard-coded in the package body; no lockout; `change_password` is a stub; authorization is grade-based (`JOB_TITLES.GRADE_ID >= 8` = everything, `>= 5` = view everything, else own leave/profile) not role-based; the "edit own department" rule in the comments is not implemented. |
| Data | 40+ tables, `ACTIVE_FLAG` soft deletes, audit columns everywhere, `AUDIT_LOG` with JSON-in-CLOB, `CONNECT BY` org hierarchy view | PII: `SSN_ENCRYPTED`, dependents' SSN, `EMPLOYEE_BANK_ACCOUNTS.ACCOUNT_NUMBER_ENCRYPTED`, DOB, addresses, W-4 data, photo BLOB. |
| Batch/integration | `DBMS_SCHEDULER` (monthly accrual, nightly reporting refresh), `UTL_FILE` flat files (pay register, GL journal, benefits feed, T&A import), `UTL_SMTP` notifications | Directory objects `PAYROLL_OUTPUT`, GL/benefits dirs; hard-coded SMTP host; cleartext FTP credentials in `SYSTEM_PARAMETERS` (per package comments). |
| Quality | No unit tests, no CI, Forms binaries git-ignored | Only the XML exports are versioned; the `.fmb/.pll/.mmb` binaries needed to rebuild the Forms app are not. |

---

## 2. Strategy options (evaluated once, applied per area)

| | (a) APEX lift-and-shift | (b) Spring Boot + Angular/React rewrite | (c) .NET + Blazor rewrite | (d) Hybrid: PL/SQL packages as API + new UI |
|---|---|---|---|---|
| Reuse of PL/SQL | Very high | Low (logic re-implemented in Java) | Low | High (packages exposed through ORDS or JDBC `CallableStatement`) |
| Time to first screen | Fastest | Slow | Slow | Fast |
| Fixes known logic defects | No (carries them) | Yes, with tests | Yes | Only if packages are refactored |
| Skill fit for an Oracle Forms shop | Excellent | Java is the most common target for Forms teams | Poor fit with Oracle-centric shop; `ODP.NET` fine, but two ecosystems | Good: DBAs keep PL/SQL, web team owns UI |
| Decoupling from Oracle DB | None | High | High | Low (transitional) |
| Testability | Weak (APEX unit testing immature; utPLSQL for packages) | Strong (JUnit, Testcontainers, Playwright) | Strong | Medium (utPLSQL + API contract tests) |
| Mobile / self-service UX | Adequate | Best | Good | Best (same UI stack as b) |
| Security posture | Inherits `PKG_SECURITY` unless replaced by APEX auth schemes | Spring Security + external IdP | ASP.NET Identity / IdP | Depends on new UI layer's auth, but DB-side `has_permission` still enforced |
| Licence / infra | Free with DB; removes WebLogic Forms licence | JVM infra, ORDS optional | Windows/Linux .NET infra | ORDS or Spring Boot |

Cross-cutting decisions that apply regardless of area:

1. **Target stack for the new UI/API layer is Spring Boot 3 (Java 21) + Angular** (the user's requested Java/web target). A single stack across all areas avoids the dual-runtime cost that makes (c) unattractive here.
2. **Hybrid (d) is the transitional architecture, not the end state.** Packages are exposed through a Spring Boot service tier (`JdbcTemplate`/`SimpleJdbcCall`) so the same API surface can later be re-implemented in Java without changing the Angular client (strangler-fig).
3. **Security is replaced first, not migrated.** `PKG_SECURITY.authenticate` never checks a password; there is nothing to lift.
4. **APEX is retained as an option only for `HRMS_REPORTS` and `HRMS_ADMIN`** (*inferred* - modules not in repo). Interactive Reports over the existing `VW_*` views and `PKG_REPORTING` ref cursors is where APEX is cheapest and the UX bar is lowest. This is optional and not on the critical path.

---

## 3. Area-by-area evaluation and recommendation

### 3.1 Employee management (`HRMS_EMPLOYEE`, `PKG_EMPLOYEE`, `trg_employees.sql`, `HRMS_VALIDATION_LIB`)

Source facts that drive the decision:

- `PKG_EMPLOYEE` is the largest package (966 lines) and encodes real lifecycle rules: manager-cycle detection (depth 15), department must be active, `terminate_employee` cascades to `SALARY_RECORDS`, `EMPLOYEE_PAY_ELEMENTS`, pending `LEAVE_REQUESTS`, `EMPLOYEE_HISTORY`, `AUDIT_LOG` and notifications; `rehire_employee` is the only legal path back from `TERMINATED` (enforced by `TRG_EMP_BEFORE_UPDATE`).
- The form itself bypasses most of that: the `EMPLOYEE` block does direct table DML (`DMLDataTargetName="HRMS.EMPLOYEES"`), and `PRE-INSERT` calls only `generate_emp_number`. Termination/transfer through the form is therefore a plain `UPDATE`, with history written by DB triggers instead of the package.
- Rule drift: form says hire date <= today+90, DB trigger says <= today+180; form uppercases names via `CaseRestriction`, package uses `UPPER(TRIM())`.
- Defects: SQL injection in `search_employees`, `MAX()+1` employee number, `CONNECT BY` org chart warned as slow above 500 employees, `SELECT *` `%ROWTYPE` usage in callers.
- Deep coupling to payroll (`create_employee` -> `PKG_PAYROLL.create_salary_record`) and vice versa.

| Option | Assessment |
|---|---|
| (a) APEX | Master-detail with 4 tabs and 8 LOVs is a natural APEX form; but it would inherit the three-way rule duplication and would still need the package/trigger clean-up. Low value beyond removing WebLogic. |
| (b) Full rewrite | Feasible, but re-implementing the termination cascade and the hierarchy validation in Java while payroll still lives in PL/SQL splits one transaction across two runtimes. |
| (c) .NET | Same as (b) with worse ecosystem fit. |
| (d) Hybrid | Expose `create_employee`, `update_employee`, `transfer_employee`, `promote_employee`, `terminate_employee`, `rehire_employee`, `get_org_chart`, `get_headcount_by_dept` as REST via Spring Boot; new Angular UI **must** call the package procedures instead of doing table DML, which alone removes the form/trigger drift. Re-implement `search_employees` in Java with bound parameters (do not expose the PL/SQL version). |

**Recommendation: (d) Hybrid**, with two mandatory package fixes before exposure (replace `generate_emp_number` with `SEQ_EMPLOYEE`-backed formatting; delete or rewrite `search_employees`) and one architectural fix (break the `PKG_EMPLOYEE` <-> `PKG_PAYROLL` cycle - see RISK_REGISTER R-02). Once payroll is migrated (Phase 4), lifecycle logic can be pulled into Java as a second step without UI change.

### 3.2 Payroll (`HRMS_PAYROLL`, `PKG_PAYROLL`, `trg_audit.sql` salary trigger)

Source facts:

- Calculation is a set of pure functions (`calculate_federal_tax`, `calculate_state_tax`, `calculate_fica`, `calculate_medicare`) plus an orchestrator that writes one `PAYROLL_DETAILS` row per element. All constants are hard-coded for tax year 2024 (`c_ss_wage_base = 168600`, standard deductions 14600/29200, allowance 4300, `11600/47150/100525...` brackets, flat state rates) even though a `TAX_BRACKETS` table exists. Only `SINGLE|MARRIED_SEPARATE` and `MARRIED_JOINT` brackets exist; any other filing status yields zero federal tax.
- `calculate_payroll` is row-by-row, commits every 50 employees, and marks the run `ERROR` if any employee fails - a failed run is left half-written and must be `reverse_payroll`ed.
- YTD handling is inconsistent: `calculate_employee_pay` uses `get_ytd_earnings` correctly for FICA caps, but `get_payslip` returns `0 AS YTD_GROSS/YTD_NET`.
- State machine: `PENDING -> CALCULATING -> CALCULATED|ERROR -> APPROVED -> PAID`, `REVERSED` from anywhere after calculation. Approval requires `PAYROLL/APPROVE` permission (checked in the form, not the package).
- Output is `UTL_FILE` CSV to directory object `PAYROLL_OUTPUT`; GL journal in `PKG_INTEGRATION` depends on approved runs.
- The form is thin: 3 tabs, 3 buttons that call `create_payroll_run`, `calculate_payroll`, `approve_payroll`.

| Option | Assessment |
|---|---|
| (a) APEX | Thin UI over three package calls is trivially APEX-able, but the risk here is not the UI, it is the calculation. APEX adds nothing to correctness. |
| (b) Full rewrite | Highest-value long-term (tax engine as a versioned, unit-tested Java module reading `TAX_BRACKETS`), but highest risk to cut over in one step: every cent must reconcile with legacy for a full year of periods. |
| (c) .NET | As (b). |
| (d) Hybrid | Keep `PKG_PAYROLL` as the calculation system of record; new Angular UI + Spring Boot API for period/run management, approval (now enforced server-side), payslip and register download (replace `UTL_FILE` with API-streamed CSV). Enables a **parallel-run** of a Java tax engine against PL/SQL results before switching. |

**Recommendation: (d) Hybrid first, then (b) for the calculation engine.** Phase 4 in the cutover plan wraps `PKG_PAYROLL`; a Java `TaxEngine` is built alongside and shadow-run against every legacy run until N consecutive periods reconcile to the cent, then the Java engine becomes primary and `PKG_PAYROLL.calculate_*` is retired. Prerequisites in PL/SQL: move brackets/rates into `TAX_BRACKETS` (the `TODO` already in source), wrap `calculate_payroll` in a single transaction or make it restartable per employee.

### 3.3 Leave management (`HRMS_LEAVE`, `PKG_LEAVE`, `trg_audit.sql` leave trigger)

Source facts:

- Bounded, self-contained domain: 673 lines, depends only on `EMPLOYEES` (read), `PKG_NOTIFICATION`, `PKG_AUDIT`. No coupling to payroll.
- Ledger semantics are simple and well-defined: `AVAILABLE = OPENING + ACCRUED - USED + ADJUSTMENT - PENDING`; submit moves days to `PENDING`, approve moves `PENDING -> USED`, reject/cancel release. Auto-approve when `LEAVE_TYPES.REQUIRES_APPROVAL = 'N'`.
- Known defects are business-visible and easy to fix in a rewrite with tests: half-day sets `TOTAL_DAYS = 0.5` regardless of range; overlap check ignores half-day period; observed holidays not handled; `expire_carryover` not idempotent; back-dating allowed up to 5 days; `HOLIDAYS.LOCATION_CODE` filter.
- Batch: `run_monthly_accrual` (DBMS_SCHEDULER, commits every 100), `process_carryover`, `expire_carryover`.
- Highest self-service demand (every employee submits and views requests; managers approve) - the area where a modern, mobile-friendly UI has the most user value.

| Option | Assessment |
|---|---|
| (a) APEX | Good fit for the request/approval screens, but the accrual/carryover bugs remain in PL/SQL and the mobile UX is average. |
| (b) Full rewrite | Small, well-understood domain; ledger logic is trivially unit-testable; fixes defects at the source; scheduled jobs move to Spring `@Scheduled`/Quartz with idempotency keys. |
| (c) .NET | As (b), worse fit. |
| (d) Hybrid | Works, but leaves the defect list untouched and you would still rewrite later. |

**Recommendation: (b) Rewrite in Spring Boot + Angular.** Keep the tables (they are sound; add a unique key on `LEAVE_ACCRUAL_LOG(EMP_ID, LEAVE_TYPE_ID, ACCRUAL_DATE)` for idempotency). `PKG_LEAVE` remains deployed but read-only during coexistence so the Forms `HRMS_EMPLOYEE.terminate` cascade (`UPDATE LEAVE_REQUESTS ... CANCELLED`) still works until employee management is migrated.

### 3.4 Performance reviews (`HRMS_PERFORMANCE`, `PKG_PERFORMANCE`)

Source facts:

- Smallest domain (320 lines). Entities: `REVIEW_CYCLES` (`DRAFT -> OPEN -> CLOSED`), `PERFORMANCE_REVIEWS` (`NOT_STARTED -> SELF_REVIEW -> MANAGER_REVIEW -> COMPLETED -> ACKNOWLEDGED`), `PERFORMANCE_GOALS`.
- Rules: rating 1.0-5.0 mapped to five labels at 4.5/3.5/2.5/1.5; goal status derived from progress %; reviews auto-generated for every active employee with a manager (`DUP_VAL_ON_INDEX` swallowed).
- Weak enforcement in current code: `submit_manager_review` and `close_review_cycle` have no status guard; the form allows direct `UPDATE` of `PERFORMANCE_REVIEWS` and `INSERT` of goals (bypassing the package).
- No money, no regulated PII (review text is sensitive HR data, but not SSN/bank), no coupling to payroll or leave, no batch job. Notifications only.

| Option | Assessment |
|---|---|
| (a) APEX | Easy, but CLOB-heavy multi-step workflow UX is better served by a component framework. |
| (b) Full rewrite | Lowest risk in the estate; clean state-machine to model; ideal first delivery to prove the platform (auth, API conventions, CI, test data, observability). |
| (c) .NET | As (b). |
| (d) Hybrid | No benefit - package is smaller than the API wrapper would be. |

**Recommendation: (b) Rewrite in Spring Boot + Angular, and use it as Phase 1** (see CUTOVER_PLAN). Tighten the state machine while doing so (guards on manager submit and cycle close).

### 3.5 Security (`HRMS_LOGIN`, `HRMS_MENU`, `PKG_SECURITY`, `USER_SESSIONS`)

Source facts:

- `authenticate(p_username, p_password, ...)` looks up an active employee by email and creates a `USER_SESSIONS` row; **`p_password` is never compared to anything**. Whatever the production `USER_CREDENTIALS` table looks like, it is not in this repo and the checked-in logic cannot be trusted as a spec.
- `hash_password` = unsalted MD5. `encrypt_ssn/decrypt_ssn` = AES-256-CBC with a key literal in the package body (in source control). `change_password` validates complexity and then does nothing.
- Authorization is `has_permission(emp, module, action)` derived from `JOB_TITLES.GRADE_ID` only (grade >= 8 all, grade >= 5 any `VIEW`, everyone `LEAVE CREATE/VIEW` and `EMPLOYEE VIEW`); no department or manager-of-record rule exists, so leave approval is effectively grade >= 8 in the package while the form filters the queue by manager. Forms enforce it client-side (`SET_MENU_ITEM_PROPERTY`, `SET_BLOCK_PROPERTY`) and packages do **not** re-check (e.g. `approve_payroll` trusts the caller).
- Session: 30-minute DB-time timeout, validated per form via `is_session_valid`; the login form transmits the password in cleartext (per form comment); no lockout, no MFA.

| Option | Assessment |
|---|---|
| (a) APEX | APEX authentication schemes (LDAP/SSO/social) would replace `authenticate`, but authorization would need re-implementation anyway. |
| (b) Rewrite | Spring Security resource server + OpenID Connect to the corporate IdP (Entra ID / Okta / Keycloak); roles/claims map to a new `HRMS_ROLES`/`HRMS_PERMISSIONS` model; `has_permission` logic ported to a policy class and enforced in the API (`@PreAuthorize`), not the UI. |
| (c) .NET | As (b). |
| (d) Hybrid | Not applicable: there is no correct behaviour to keep. |

**Recommendation: (b) Replace.** Deliver as Phase 0 platform work. Retain `PKG_SECURITY.decrypt_ssn` only as a one-time migration utility to re-encrypt PII under a key held in a KMS/Vault (see RISK_REGISTER R-03). Keep `USER_SESSIONS` populated by the API for audit continuity during coexistence.

---

## 4. Recommendation summary

| Area | Strategy | Phase | Why in one line |
|---|---|---|---|
| Security | (b) Replace with Spring Security + OIDC | 0 | Legacy auth verifies nothing; must be rebuilt before any new UI ships |
| Performance reviews | (b) Rewrite | 1 | Smallest, decoupled, no money/regulated PII: lowest-risk pilot |
| Leave | (b) Rewrite | 2 | Small ledger domain with known bugs; biggest self-service payoff |
| Employee management | (d) Hybrid -> later Java | 3 | Rich lifecycle logic in PL/SQL, coupled to payroll; wrap now, port after payroll |
| Payroll | (d) Hybrid -> (b) engine via parallel run | 4 | Financial parity risk; wrap, shadow-run Java engine, then switch |
| Reports / Admin (*not in repo*) | (a) APEX optional, or Angular over `PKG_REPORTING` | 5 | Read-mostly over existing views; lowest UX bar |

## 5. Target architecture (end state)

```
Browser (Angular 18, PWA)
   |  HTTPS + OIDC (PKCE)
API gateway / Spring Boot 3 modular monolith  (hrms-api)
   |-- security      : Spring Security, JWT validation, permission policy (ported has_permission)
   |-- performance   : Java domain (Phase 1)
   |-- leave         : Java domain + scheduled accrual/carryover (Phase 2)
   |-- employee      : Phase 3 = JDBC facade over PKG_EMPLOYEE; Phase 6 = Java domain
   |-- payroll       : Phase 4 = facade over PKG_PAYROLL + shadow TaxEngine; Phase 6 = Java engine
   |-- reporting     : read models over VW_* views / PKG_REPORTING ref cursors
   |-- audit         : writes AUDIT_LOG (keeps trigger-based audit for direct DML during coexistence)
   |-- notification  : outbox table -> SMTP relay (replaces UTL_SMTP)
   |-- integration   : GL/benefits exports as API-generated files to object storage / SFTP (replaces UTL_FILE)
Oracle 19c HRMS schema (unchanged tables; packages retained only where wrapped)
Observability: OpenTelemetry -> existing APM; structured logs with PII redaction
```

Data stays in Oracle throughout. The Forms application and the new UI share the schema during coexistence; the rule "write only through packages or through the new API, never direct table DML from a UI" is what keeps the two consistent.
