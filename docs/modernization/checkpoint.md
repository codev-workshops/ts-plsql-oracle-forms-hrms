# HRMS Modernization — Program Checkpoint

> **Single entry point for every phase.** Read this file first. Locked decisions (§2) and the
> API contract (once frozen) are binding; changing them requires a versioned amendment (§7),
> never a silent edit. Detailed catalogue backing the registry: [`inventory.md`](inventory.md).

| Field | Value |
|---|---|
| `contract_version` | **`v1.1-P4`** (v1 frozen by P3; additive amendment A-P4-01 by P4, renumbered by the coordinator when P4 and P5 merge) |
| `checkpoint_status` | `in-progress` |
| `last_updated_by_phase` | `P4` |
| Source of truth | The files under `schema/`, `plsql/`, `forms/`, `data/` at `main` (`de01258`). The README overstates what the repo contains: it claims 42 tables, 15 views and 200+ triggers, but the files have 30 tables, 6 views and 6 triggers. Treat the README as non-authoritative. |

---

## 1. How to use this file (phase protocol)

1. Read §2 (locked decisions), §3 (contract version), §4 (ledger), §5 (registry), §6 (open questions).
2. Do only your phase's work. Registry entries whose `Dest` is your phase are your scope.
3. On exit, update **all** of the following in the same PR:
   - §4: add or update your ledger row with status `submitted` and your commit SHAs. The
     coordinator marks it `accepted` in the next phase.
   - §5: set `Status` for each entry you touched (vocabulary in §5.0) and give the evidence
     (a commit, file or test).
   - §6: close the questions you resolved, citing the decision. Add new questions.
   - §2 and §3: change these only through §7 amendments.
4. The `api` IDs used below are logical names, not final routes. Phase 3 freezes the real routes in
   `docs/modernization/openapi-v1.yaml`.

---

## 2. Locked decisions

Each decision has an ID (`D-xx`). Cite it by ID. To change one, add an amendment in §7.

### D-00 Target application stack — **USER DECISION** (relayed by the coordinator, 2026-11-20)

| Layer | Choice |
|---|---|
| Backend language and framework | **Java 21 + Spring Boot 3** |
| Database | **PostgreSQL 16** |
| Schema migrations | **Flyway** (versioned SQL migrations, `V<n>__<desc>.sql`) |
| API style | **REST/JSON, with the contract defined in OpenAPI 3.x** (`docs/modernization/openapi-v1.yaml`, frozen at v1 in P3) |
| Frontend | **React** (replaces the Oracle Forms UI) |

Implications for later phases (recommended defaults, not user decisions):
- P1 writes Flyway SQL migrations. P1 chooses the Maven/Gradle layout and records it in §2 as an
  amendment if it is not obvious.
- Server-side logic lives in Spring services. **No business logic goes in DB triggers or stored
  procedures** (see D-07). DB constraints (PK/FK/CHECK/UNIQUE/generated columns) still enforce
  integrity.
- React screens call the REST API only. Every Forms-only gate is re-homed in the API (§5), and
  the UI may at most mirror a gate for UX.

### D-01 Oracle → PostgreSQL type mapping

| Oracle | PostgreSQL | Rule |
|---|---|---|
| `NUMBER(p,0)` surrogate PK / FK id (`*_ID`) | `BIGINT` | All surrogate keys and their FKs. |
| `NUMBER(p,s)` money / rate / days / rating | `NUMERIC(p,s)` | Keep the precision and scale exactly. Never use `float`/`double`/`real`. |
| `NUMBER` (unconstrained) | `NUMERIC` | |
| `NUMBER(p)` small counters/years (e.g. `CALENDAR_YEAR`, `PRIORITY`, `EMPLOYEE_COUNT`) | `INTEGER` | P1 lists each column it maps this way. |
| `VARCHAR2(n)` | `VARCHAR(n)` | Length in characters. Audit actor columns are widened (D-04). |
| `CHAR(1)` Y/N flag (`*_FLAG`, `OVERTIME_ELIGIBLE`, `BENEFITS_ENROLLED`, `PRENOTE_SENT`, `REQUIRES_*`, `EMPLOYER_PAID`) | `BOOLEAN` | The flag policy is below. |
| `CHAR(1)` non-flag code (`GENDER`) | `VARCHAR(1)` + `CHECK` | This is not a boolean. |
| `DATE`, calendar-date semantics | `DATE` | `HIRE_DATE`, `TERMINATION_DATE`, `DATE_OF_BIRTH`, `START_DATE`, `END_DATE`, `EFFECTIVE_DATE`, `PERIOD_START_DATE`, `PERIOD_END_DATE`, `PAY_DATE`, `HOLIDAY_DATE`, `TARGET_DATE`, `ACCRUAL_DATE`, `CARRYOVER_EXPIRY_DT`, review-cycle due dates |
| `DATE`, event/instant semantics | `TIMESTAMPTZ` (stored in UTC) | `CREATED_DATE`, `MODIFIED_DATE`, `CHANGED_DATE`, `LOGIN_TIME`, `LOGOUT_TIME`, `APPROVAL_DATE`, `APPROVED_DATE`, `SUBMITTED_DATE`, `CANCELLED_DATE`, `CLOSED_DATE`, `RUN_DATE`, `EMPLOYEE_ACK_DATE`, `SENT_DATE`, plus any other `*_TIME` or audit/event column |
| `CLOB` free text (assessments, comments, notes, notification `BODY`) | `TEXT` | |
| `CLOB` holding JSON (`AUDIT_LOG.OLD_VALUES`, `AUDIT_LOG.NEW_VALUES`) | `JSONB` | Audit payloads are real JSON objects (D-04, D-08). |
| `BLOB` (`EMPLOYEES.PHOTO_BLOB`) | `BYTEA` | |
| `LEAVE_BALANCES.AVAILABLE NUMBER(6,2) GENERATED ALWAYS AS (OPENING_BALANCE + ACCRUED - USED + ADJUSTMENT - PENDING) VIRTUAL` | `NUMERIC(6,2) GENERATED ALWAYS AS (opening_balance + accrued - used + adjustment - pending) STORED` | PostgreSQL 16 has no virtual generated columns. The formula must stay identical. |
| `DEFAULT SYSDATE` | `DEFAULT CURRENT_TIMESTAMP` (timestamptz) / `CURRENT_DATE` (date) | Only a safety net. The application supplies the values (D-04). |
| `CHECK (x IN ('A','B', NULL))` | `CHECK (x IS NULL OR x IN ('A','B'))` | Make the NULL semantics explicit. |
| Schema `HRMS`, upper-case identifiers | Schema `hrms`, unquoted lower `snake_case` | Keep the column names, lower-cased. |

**CHAR(1) flag policy:** `'Y'` maps to `true` and `'N'` maps to `false`. A column that is
`NOT NULL` in Oracle stays `NOT NULL`. A nullable flag that has an Oracle default becomes
`NOT NULL DEFAULT <same value>`, and any NULLs are backfilled to that default during data
migration (P8 rehearsal verifies this). The API exposes JSON booleans. The old string `'Y'`/`'N'`
never appears in the API.

**Calendar-date vs instant:** if P1 finds a `DATE` column that the list above does not classify,
default it to `DATE` when the name ends in `_DATE` and holds a business date, otherwise to
`TIMESTAMPTZ`, and list it in the P1 ledger notes. Business dates have no timezone. Instants are
stored in UTC.

### D-02 Sequence and ID strategy

- Surrogate PKs use `BIGINT GENERATED BY DEFAULT AS IDENTITY`, so seed and migrated rows can keep
  their original IDs. The start value is at least the Oracle `START WITH`. After seed or data load,
  each identity is reset to `max(id)+1` with `setval`. This is mandatory because the seed IDs
  collide with sequences that start at 1 (inventory §I-6).
- **`EMP_NUMBER` comes from a dedicated sequence `hrms.seq_emp_number`**, formatted as
  `'EMP-' || lpad(nextval::text, 6, '0')`. This drops the `MAX()+1` race in
  `PKG_EMPLOYEE.generate_emp_number`, together with its silent fallback to `SEQ_EMPLOYEE`. Before
  go-live, the sequence is set above the largest numeric suffix already in use. The `UNIQUE`
  constraint on `emp_number` stays. Error `-20002` (duplicate generated number) becomes
  unreachable and is kept only as a defensive 409.
- Number formatting stays `EMP-NNNNNN` (regex `^EMP-\d{6}$`, from
  `PKG_VALIDATION.validate_emp_number_format`). If the suffix exceeds 999999 that is an error,
  not a silent widening.
- No table uses `MAX()+1` or `SELECT ... FROM DUAL` patterns.

### D-03 Soft-delete convention

- **No physical deletes of business rows through the application.** This covers `employees` and
  every table with an `active_flag`. The API exposes no `DELETE` on these resources; such a request
  returns `405` with code `DELETE_FORBIDDEN` (legacy `-20504`).
- Deactivation sets `active_flag = false`. Employees leave only through **terminate**, which sets
  `employment_status = 'TERMINATED'` and `active_flag = false`. They return only through
  **rehire**.
- **Default reads filter to active rows** (this re-homes Forms `DEFAULT_WHERE`). For employees the
  filter is `employment_status = 'ACTIVE' AND active_flag`. Inactive rows are returned only with an
  explicit query parameter (e.g. `includeInactive=true`), which needs the module's `VIEW`
  permission.
- At the DB level, P1 enforces this with grants (the application role has no `DELETE` on
  `employees`) rather than a trigger. Exceptions (audit retention purge, test fixtures) must be
  listed in §2 by amendment.

### D-04 Audit-column ownership (`CREATED_BY`/`CREATED_DATE`/`MODIFIED_BY`/`MODIFIED_DATE`)

- **The application owns these columns.** They are set from the authenticated principal and from an
  injected `java.time.Clock`. Nothing uses DB `USER`, `SYSDATE` or `SYS_CONTEXT`, and no DB
  trigger fills them.
- The actor value is the principal's login identifier (the employee email, lower-cased). Batch and
  system jobs use `system:<job-name>`. Actor columns widen to `VARCHAR(100)`, because Oracle's
  `VARCHAR2(30)` cannot hold the email usernames that the legacy code already writes there.
- `created_*` is written once and never updated. `modified_*` is set on every update.
- The column-level DB defaults (`CURRENT_TIMESTAMP`) are only a safety net. Tests must not rely on
  them.
- Audit-log rows (`audit_log`) are written in the **same transaction** as the change they
  describe. The legacy `PRAGMA AUTONOMOUS_TRANSACTION`, which swallowed errors, is not ported (see
  D-08).

### D-05 Error mapping: `RAISE_APPLICATION_ERROR -20xxx` → HTTP

The error body is RFC 9457 `application/problem+json`, with extension members `code` (a stable
string), `legacyCode` (the Oracle number, or null), `traceId`, and optionally `errors[]` for field
validation. The `code` strings are part of the contract.

| Legacy | Source | `code` | HTTP |
|---|---|---|---|
| -20001 | PKG_EMPLOYEE (not found) | `EMPLOYEE_NOT_FOUND` | 404 |
| -20001 | PKG_LEAVE.submit (not found **or not active**) | `EMPLOYEE_NOT_FOUND` / `EMPLOYEE_NOT_ACTIVE` | 404 / 422 (split by cause) |
| -20002 | PKG_EMPLOYEE (duplicate emp number) | `EMP_NUMBER_CONFLICT` | 409 |
| -20003 | validate_dept | `INVALID_DEPARTMENT` | 422 |
| -20004 | validate_manager (inactive) | `INVALID_MANAGER` | 422 |
| -20004 | validate_manager (cycle) | `CIRCULAR_REPORTING` | 422 |
| -20005 | terminate (already terminated) | `ALREADY_TERMINATED` | 409 |
| -20010 | create_employee (names required) | `VALIDATION_FAILED` | 400 |
| -20011 | create_employee (job) | `INVALID_JOB` | 422 |
| -20012 | transfer (non-active) | `EMPLOYEE_NOT_ACTIVE` | ~~409~~ 422 (A-P3-02) |
| -20101 | create_salary_record (≤0) | `INVALID_SALARY` | 422 |
| -20102 | close_pay_period / create_payroll_run | `PERIOD_CLOSED` | 409 |
| -20103 | approve_payroll | `INVALID_RUN_STATUS` | 409 |
| -20104 | calculate_employee_pay | `NO_ACTIVE_SALARY` | 422 |
| (new) | calculate gate (FRM-062) | `INVALID_RUN_STATUS` | 409 |
| -20201 | submit leave (balance) | `INSUFFICIENT_LEAVE_BALANCE` | 422 |
| -20202 | submit leave (overlap) | `LEAVE_OVERLAP` | 409 |
| -20203 | submit leave (type) | `INVALID_LEAVE_TYPE` | 422 |
| -20203 | submit leave (tenure) | `MIN_TENURE_NOT_MET` | 422 |
| -20204 | approve/reject/cancel leave | `INVALID_REQUEST_STATUS` | 409 |
| -20210 | submit leave | `INVALID_DATE_RANGE` | 400 |
| -20211 | submit leave | `BACKDATE_LIMIT_EXCEEDED` | 422 |
| -20212 | submit leave | `NO_BUSINESS_DAYS` | 422 |
| -20301 | authenticate | `INVALID_CREDENTIALS` | 401 |
| -20310/-20311/-20312 | change_password | `PASSWORD_POLICY` | 400 |
| -20401 | open_review_cycle | `INVALID_CYCLE_STATUS` | 409 |
| -20402 | submit_self_assessment | `INVALID_REVIEW_STATUS` | 409 |
| -20403 | submit_manager_review | `INVALID_RATING` | 400 |
| -20501 | TRG_EMP_BEFORE_INSERT | `HIRE_DATE_TOO_FAR` | 422 |
| -20502 | TRG_EMP_BEFORE_INSERT | `EMAIL_IN_USE` | 409 |
| -20503 | TRG_EMP_BEFORE_UPDATE | `REACTIVATION_FORBIDDEN` | 409 |
| -20504 | TRG_EMP_INSTEAD_OF_DELETE | `DELETE_FORBIDDEN` | 405 |
| -20900 | PKG_COMMON.set_param | `PARAM_NOT_EDITABLE` | 404 / 409 |
| (new) | missing, invalid or expired token (replaces Forms session checks) | `UNAUTHENTICATED` | 401 |
| (new) | permission matrix denies (replaces Forms `has_permission` gates) | `FORBIDDEN` | 403 |
| (new) | generic bean validation | `VALIDATION_FAILED` | 400 |
| (new, A-P2-01) | change_password: the current password does not match | `INVALID_CURRENT_PASSWORD` | 422 |
| (new) | optimistic-lock / `FOR UPDATE NOWAIT` conflict | `CONCURRENT_MODIFICATION` | 409 |

Rules: `4xx` means the client can act on it, `5xx` means it cannot. Messages never include SQL,
stack traces or other employees' data. Status codes 400 vs 422 follow the split above: 400 is a
malformed or missing input, 422 is input that is well-formed but violates a business rule, and 409
is a state conflict.

### D-06 Auth model (token + module/action permission matrix; fix, not port)

- **Login:** the client posts credentials to an auth endpoint. Identity is resolved via
  `lower(employees.email) = lower(:username)` with `employment_status = 'ACTIVE'`, which matches the
  legacy `PKG_SECURITY.authenticate`. If more than one active row matches, the legacy code picks
  `MIN(emp_id)`. P2 ports that deterministically and records it, and OQ-06 tracks the question.
- **Fix, not port:** the legacy `authenticate` **never checks the password** (see inventory §I-4).
  `hash_password` uses MD5 and is never called, and the SSN key `c_encryption_key` is hard-coded in
  the source. The new system does the following:
  - Credentials live in a new `user_credentials` table holding a **BCrypt or Argon2id** hash (Spring
    Security `DelegatingPasswordEncoder`). The MD5 path is dropped.
  - The SSN encryption key comes from external configuration or a secret store and is **never in
    source**. Re-encryption of `ssn_encrypted` is part of P8 data migration. `decrypt_ssn`'s habit
    of swallowing errors (`'***DECRYPT_ERROR***'`) is not ported.
  - Responses are uniform and timing-safe: the same `INVALID_CREDENTIALS` comes back for an unknown
    user and for a bad password.
  - Brute-force lockout is added. The policy numbers are OQ-07 (provisional: 5 failures, 15-minute
    lock).
- **Token:** an opaque, random bearer token with at least 256 bits of entropy. Only its SHA-256 hash
  is stored, in `user_sessions`. Sessions are server-side and revocable, so `logout` invalidates the
  token. The legacy timeout is **30 minutes from `LOGIN_TIME` (absolute)**. P2 ports that value, and
  OQ-08 tracks absolute vs. sliding.
- **Authorization:** a data-driven **permission matrix** of
  `(module ∈ {EMPLOYEE, PAYROLL, LEAVE, PERFORMANCE, REPORTS, ADMIN}) × (action ∈ {VIEW, CREATE,
  EDIT, APPROVE, ADMIN})`. It is checked server-side on **every** endpoint and returns 403
  `FORBIDDEN` on denial. Its initial population must reproduce the legacy
  `PKG_SECURITY.has_permission` exactly, for parity:
  1. Grade ≥ 8 → all modules, all actions.
  2. `VIEW` on any module → grade ≥ 5.
  3. `LEAVE` `CREATE`/`VIEW` → everyone.
  4. `EMPLOYEE` `VIEW` → everyone.
  5. Everything else → denied.
  Row-level scoping (own records, own department) is described in the legacy comments but is **not
  implemented** in the code. It is tracked as OQ-09 and must not be invented silently.
- The React client may hide UI by permission (re-homing `SET_MENU_ITEM_PROPERTY` and
  `SET_BLOCK_PROPERTY ... *_ALLOWED`), but the API is the only enforcement point.

### D-07 Logic placement

All re-homed triggers and Forms rules (§5) become Spring service, domain or authorization code, or
DB constraints. No new PL/pgSQL business triggers are added. A DB trigger is allowed only for pure
integrity work that a constraint cannot express, and each one needs an amendment.

### D-08 Audit / CDC

- `audit_log` rows are written by the application in the same transaction as the change.
  `action_type` is limited to `INSERT`/`UPDATE`/`DELETE`/`STATUS_CHANGE`. The last value is new:
  the legacy CHECK constraint rejected it, so leave status audits were silently lost (DEF-15).
- `old_values` and `new_values` are JSONB. They carry at least the fields the legacy triggers
  emitted: salary `{emp_id, salary, effective, active}`, leave `{status}`, departments
  `{action only}`.
- A failed audit write fails the business transaction. Audit writes are never silently skipped.

### D-09 Phase-ledger mechanics

The ledger format is in §4. A phase records the SHAs of its **content commits**. The final commit,
which only writes the ledger row, is excluded because it cannot contain its own SHA. The PR head SHA
is reported in the PR and the final message.

### D-10 Backend build layout and migration conventions (P1, amendment A-P1-01)

- **Build:** one Maven module in `backend/` (`backend/pom.xml`), parent
  `spring-boot-starter-parent` 3.3.5, Java 21, groupId `com.hrms`, base package `com.hrms`
  (`com.hrms.HrmsApplication`). Later phases add packages per module (e.g. `com.hrms.auth`,
  `com.hrms.leave`) inside this module; splitting into several Maven modules needs an amendment.
- **Migrations:** `backend/src/main/resources/db/migration/V<n>__<desc>.sql`, one global,
  strictly increasing version sequence. P1 owns V1–V8. Each later phase appends `V<next>__…`;
  an applied migration is never edited (Flyway `validate` fails on checksum drift).
- **Dev/test seed:** `backend/src/main/resources/db/seed/R__dev_seed.sql`, a repeatable,
  idempotent (`ON CONFLICT DO NOTHING`) migration **generated** from `data/seed/*.sql` by
  `backend/scripts/port_seed.py`. It is loaded only with the `dev` Spring profile or in tests
  (`spring.flyway.locations=classpath:db/migration,classpath:db/seed`), never in production.
  `data/seed/*.sql` stays the Oracle original. Phases that need more fixture rows add them to the
  generator or to their own test fixtures, not to `db/migration`.
- **Ownership and grants:** the Flyway user owns every object in schema `hrms`. The application
  connects as a login role that is a member of the NOLOGIN role `${app_role}` (Flyway
  placeholder, default `hrms_app`; env `HRMS_APP_ROLE`). V8 grants it SELECT/INSERT/UPDATE on
  all tables, USAGE/SELECT on sequences, and default privileges so later tables get the same.
  It has **no DELETE or TRUNCATE anywhere** (D-03). A phase that needs a physical delete on a
  table without `active_flag` (e.g. the audit purge, PKG-A02) grants it in its own migration and
  records a D-03 exception amendment.
- **Tests:** JUnit 5 + Spring Boot test against an embedded PostgreSQL **16.4** (zonky
  `embedded-postgres` 2.0.7 with the 16.4.0 binaries BOM; no Docker). Run
  `mvn -f backend/pom.xml test`. Each test class gets its own database.
- **Runtime config:** `HRMS_DB_URL`, `HRMS_DB_USER`, `HRMS_DB_PASSWORD` (see
  `backend/src/main/resources/application.yml`).

---

## 3. Contract version

| Field | Value |
|---|---|
| `contract_version` | `v1.1-P4` |
| Contract artifact | `docs/modernization/openapi-v1.yaml` (`info.version: 1.1.0`, `info.x-contract-version: v1.1-P4`) |
| Frozen? | **Yes — frozen at v1 by P3 on 2026-11-20 (A-P3-01).** P4–P8 add routes only through `v1.N` amendments in §7; `OpenApiContractTest` keeps the file and the controllers in sync. |
| Freeze rule | P3 sets `contract_version: v1` and records the freeze in §7. After that, any change to paths, schemas, `code` values or status codes requires an amendment entry `v1.N` (additive) or `v2` (breaking) with rationale, and an update to the version field. |
| Version history | `v0-draft` (P0, initial). P2: first draft of the file (auth, menu, employee read, lookups; 10 operations). Still `v0-draft`. **P3: `v1` frozen** — P2 routes unchanged, plus 11 leave operations under `/api/v1/leave` and the complete `ErrorCode` catalogue for every phase (A-P3-01, A-P3-02). **P4: `v1.1-P4`** — additive: 20 performance operations under `/api/v1/performance`, no v1 route/schema/code/status changed (A-P4-01) |

---

## 4. Accepted-commits phase ledger

Status vocabulary: `pending` → `in-progress` → `submitted` (by the phase) → `accepted` or
`rejected` (by the coordinator, recorded in the next phase).

| Phase | Status | Branch | PR | Content commit SHAs | Contract version at exit | Date | Notes |
|---|---|---|---|---|---|---|---|
| P0 Inventory & checkpoint | accepted | `modernization/p0-inventory` | #81 | `6367f7847ee5` | v0-draft | 2026-11-20 | Docs only. No code or schema changes. PR head `1f84ad21df5e`. Accepted by the coordinator (recorded by P1). |
| P1 Postgres schema | accepted | `modernization/p1-schema` | #82 | `086b16f41d7d` | v0-draft | 2026-11-20 | Flyway V1–V8 + dev seed + embedded-PG16 tests (`mvn -f backend/pom.xml test`, 17 tests). No API changes. Details: §4.1. PR head `05cb2c1369d0`. Accepted by the coordinator after re-running `mvn -f backend/pom.xml test` green (recorded by P2). |
| P2 Auth + employee read model | accepted | `modernization/p2-auth` | #83 | `de31dad1ffd4` | v0-draft | 2026-11-20 | Flyway V9 (`user_credentials`, `permission_rules`, token/expiry on `user_sessions`), auth/session/matrix/menu/change-password/employee-read API, draft `openapi-v1.yaml`. `mvn -f backend/pom.xml test`: 53 tests green. No frontend (optional). Details: §4.2. PR head `c20410949c73`. Accepted by the coordinator after re-running `mvn -f backend/pom.xml test` (53 green) (recorded by P3). |
| P3 Leave pilot + contract freeze | accepted | `modernization/p3-leave` | #84 | `3de4b27a40f3` (code), `da879517d1fc` (contract) | **v1** | 2026-11-20 | Flyway V10 (`leave_accrual_runs`), dev seed `R__dev_seed_p3.sql` (dev credentials + balances, OQ-15), leave service + API, notification outbox + dispatcher, monthly accrual, **OpenAPI frozen at v1**. `mvn -f backend/pom.xml test`: 66 tests green. No frontend (optional). Details: §4.3. PR head `c23ba1c04a58`. Accepted by the coordinator after re-running `mvn -f backend/pom.xml test` green (recorded by P4). |
| P4 Performance | submitted | `modernization/p4-performance` | _see PR_ | `2d4d2094c777` | **v1.1-P4** | 2026-11-20 | Flyway V200 (reserved range V200–V299), `com.hrms.performance` service + API (cycle lifecycle, review workflow, goals, rating distribution), DEF-21 performance fixes, Forms DML policy re-homed into API authz, contract amendment v1.1-P4. `mvn -f backend/pom.xml test`: 76 tests green. No frontend. Details: §4.4. Ran in parallel with P5. |
| P5 Employee core | pending | | | | | | Runs in parallel with P4, after P3. |
| P6 Payroll | pending | | | | | | Starts after P5. |
| P7 Navigation + reporting | pending | | | | | | Starts after P4 and P6. |
| P8 Parity + cutover | pending | | | | | | Last phase. Marks the checkpoint `complete`. |

### 4.1 P1 notes (Postgres schema)

**Files.** `backend/src/main/resources/db/migration/`: `V1__schema_and_app_role`,
`V2__core_tables`, `V3__payroll_tables`, `V4__leave_tables`, `V5__performance_and_system_tables`,
`V6__indexes`, `V7__views`, `V8__app_role_grants`. Seed: `db/seed/R__dev_seed.sql` (generated).
Tests: `backend/src/test/java/com/hrms/schema/` (`SchemaMigrationTest`, `ProductionMigrationTest`).
All 30 tables, 6 views and the 29 sequences are covered.

**D-01 applications that D-01 asked P1 to list.**
- `NUMBER(p)` → `INTEGER`: `calendar_year`, `tax_year`, `cycle_year`, `priority_order`
  (emergency_contacts, pay_elements, employee_bank_accounts), `notification_queue.priority`,
  `employee_count`, `error_count`, `retry_count`, `display_order`, `federal_allowances`,
  `state_allowances`, `leave_types.carryover_expiry` (months), `leave_types.min_tenure_days`.
- `NUMBER` ids → `BIGINT`: every PK/FK `*_id`, including the `NUMBER(5)` keys (`grade_id`,
  `leave_type_id`, `holiday_id`, `param_id`), plus `audit_log.record_id`,
  `notification_queue.reference_id`, `leave_accrual_log.run_id`, `salary_records.approved_by`
  (an employee id).
- `DATE` columns not in the D-01 lists: `employee_tax_info.w4_received_date` and
  `employee_bank_accounts.prenote_date` → `DATE` (business dates). `payroll_runs.run_date`,
  `pay_periods.closed_date`, `payroll_runs.submitted_date`/`approved_date`,
  `salary_records.approval_date`, `leave_requests.approval_date`/`cancelled_date`,
  `performance_reviews.employee_ack_date`, `notification_queue.sent_date`,
  `user_sessions.login_time`/`logout_time` → `TIMESTAMPTZ` (as listed in D-01).
- Flags → `BOOLEAN NOT NULL` (30 columns; 14 nullable-with-default Oracle flags became
  `NOT NULL DEFAULT`): all 16 `active_flag`, `overtime_eligible`, `benefits_enrolled`,
  `taxable_flag`, `pretax_flag`, `employer_paid`, `exempt_flag`, `prenote_sent`, `paid_flag`,
  `accrual_flag`, `requires_approval`, `requires_document`, `half_day_flag`, `floating_flag`,
  `editable_flag`. Other nullable Oracle columns with defaults (e.g. `status`,
  `employment_status`) keep their nullability, for parity.
- Actor columns → `VARCHAR(100)` (D-04): every `created_by`/`modified_by`, `changed_by`,
  `closed_by`, `submitted_by`, `payroll_runs.approved_by`, `user_sessions.username` (DEF-17).
- `audit_log.action_type` widened `VARCHAR2(10)` → `VARCHAR(20)`, because `'STATUS_CHANGE'`
  (D-08) is 13 characters. `old_values`/`new_values` are `JSONB` with a CHECK that each is a JSON
  object.
- Identities use the Oracle `START WITH` (100 for departments, job grades and job titles; 10000
  for employees; 1 elsewhere; audit keeps `CACHE 100`). `employee_tax_info` and
  `employee_bank_accounts` had no Oracle sequence; they start at 1. `SEQ_LOCATION` had no column
  to feed (`locations` uses the natural key `location_code`) and is dropped. `hrms.seq_emp_number`
  (START 1000) is the only standalone sequence (D-02).

**Schema additions beyond the Oracle DDL** (integrity only, D-07):
- FKs the Oracle DDL documented but never declared: `departments.parent_dept_id` → departments,
  `departments.manager_emp_id` → employees, `departments.location_code` → locations,
  `holidays.location_code` → locations, `salary_records.approved_by` → employees,
  `lookup_values.parent_lookup_id` → lookup_values. The seed satisfies all of them; P8 data
  migration must check legacy rows against them.
- Indexes (V6): an index on every FK column (PostgreSQL does not create them), the TRG-004
  partial unique index `uq_employees_email_active` on `lower(email) WHERE active_flag`, a
  `lower(email)` index for login (D-06), and lookup indexes for leave overlap, holidays by date
  and location, approvals by approver and status, the pending-notification queue and audit
  lookups.
- `CHECK (x IN (…, NULL))` became `x IS NULL OR x IN (…)` for `chk_accrual_freq` and
  `chk_half_day` (D-01). This **tightens** them: see DEF-24. `chk_emp_gender` is written the
  same way (same semantics as Oracle).

**View porting (V7).** `SYSDATE` → `CURRENT_DATE`. Boolean flags replace `= 'Y'`. Column names are
unchanged. Deviations from Oracle output:
- `vw_org_hierarchy`: recursive CTE. It keeps Oracle's order of evaluation: the tree is built
  over **all** employees from the roots and the ACTIVE filter applies afterwards, so an active
  employee under a terminated manager still appears. `org_path` keeps the leading `' > '` of
  `SYS_CONNECT_BY_PATH`. `is_leaf` is `BOOLEAN` (Oracle `CONNECT_BY_ISLEAF` returned 1/0).
  `ORDER SIBLINGS BY last_name` is reproduced with a per-level sort path (ties by `emp_id`). A
  `CYCLE` clause drops manager loops instead of raising ORA-01436.
- `vw_active_employees.tenure_years` reproduces `TRUNC(MONTHS_BETWEEN(…)/12, 1)` with `age()`
  (remaining days / 31). It uses the date only, not the time of day.
- `vw_pending_approvals.details` uses `trim_scale(total_days)`: `3.0` → `'3'` as in Oracle, but
  `0.5` → `'0.5'` where Oracle printed `'.5'`.
- `vw_leave_summary.available` is kept at parity: it **excludes** `pending` (DEF-23).
- Views read the DB clock (`CURRENT_DATE`), not the injected `java.time.Clock` (D-04). P2/P7
  endpoints that need a fixed clock should pass the date as a parameter or query the tables.

**Seed-vs-DDL mismatches and how P1 handled them** (`backend/scripts/port_seed.py`):

| Mismatch | Handling |
|---|---|
| `LOCATIONS.PHONE` (DDL `PHONE_NUMBER`) | Renamed to `phone_number`. |
| `JOB_GRADES.GRADE_LEVEL` (not in DDL) and missing `GRADE_CODE NOT NULL` | `grade_level` dropped; it equals `grade_id` in every row, and `has_permission` uses `GRADE_ID`. `grade_code` is derived as `'G' || lpad(level, 2, '0')` (`G01`…`G10`). |
| `SYSTEM_PARAMETERS.DESCRIPTION` (DDL `PARAM_DESCRIPTION`) | Renamed to `param_description`. |
| START-1 sequences collide with seeded IDs (salary 1–43, pay elements 1–205, leave types 1–6, holidays 1–10, system parameters 1–10) | After loading, every identity is set to `GREATEST(max(id) + 1, START WITH)` (D-02). It never lowers a start, so employees still start at 10000 and departments at 100. `seq_emp_number` is set to `GREATEST(max numeric EMP- suffix + 1, 1000)` (seed max is 99, so 1000). Test `identitiesMovedPastSeededIds`. |
| `'Y'`/`'N'` strings, `SYSDATE`, `TO_DATE(…)` | Converted to `true`/`false`, `CURRENT_TIMESTAMP`, `DATE '…'`. |
| `CREATED_BY = 'SYSTEM'` | Written as `system:seed` (D-04 `system:<job-name>`). |
| Seed header says "25 employees" | The file has **24** (23 active + EMP-000099 terminated) and 23 salary records (none for the terminated employee). DEF-25, informational. |
| `LEAVE_TYPES` SICK `ACCRUAL_RATE 0.833` into `NUMBER(6,2)` | Stored as `0.83` (Oracle rounds the same way). Recorded so P3 accrual parity tests expect 0.83. |
| Seed `UPDATE DEPARTMENTS SET MANAGER_EMP_ID` for dept 30 twice (3, then 30) | Ported as is; the final value is 30. |

**Left for later phases (schema they will add in their own migrations):** `user_credentials`,
token-hash/expiry columns on `user_sessions` and lockout counters (P2: D-06, OQ-07, OQ-08); a
carryover idempotency record (DEF-03; P3 or P8 per OQ-12); any DELETE grant (D-03 exception).

---

### 4.2 P2 notes (auth service + employee read model)

**Files.** Migration `V9__auth.sql`. Code: `com.hrms.common` (`ErrorCode`, `ApiException`,
`Problems`, `ProblemHandler`, `AuditWriter`, `ClockConfig`), `com.hrms.auth` (`AuthService`,
`BearerTokenFilter`, `SecurityConfig`, `PermissionService`, `PasswordPolicy`, `Tokens`,
`AuthController`, `MenuController`), `com.hrms.employee` (`EmployeeReadService`,
`EmployeeController`, `LookupController`). Contract: `docs/modernization/openapi-v1.yaml`.
Tests: `AuthApiTest`, `PermissionMatrixTest`, `ChangePasswordTest`, `EmployeeReadApiTest`,
`OpenApiContractTest` (the contract's paths, schema properties and enums must equal the code).

**Routes (draft).** `POST /api/v1/auth/login` (public), `POST /api/v1/auth/logout`,
`GET /api/v1/auth/me`, `POST /api/v1/auth/change-password`, `GET /api/v1/menu`,
`GET /api/v1/employees`, `GET /api/v1/employees/{empId}`,
`GET /api/v1/lookups/{departments,job-titles,locations}`. Everything except login needs
`Authorization: Bearer <token>` (401 `UNAUTHENTICATED`). Spring Security is stateless; the
server-side session is the `user_sessions` row.

**Schema (V9).**
- `user_credentials`: one row per employee (`emp_id` unique), `password_hash` in
  `DelegatingPasswordEncoder` format (`{bcrypt}…`), `failed_attempts`, `locked_until`,
  `password_changed_date`, `active_flag`. No MD5 column; `hash_password` is not ported.
- `user_sessions` gains `token_hash` (unique, SHA-256 hex of the token) and `expires_at`, and a
  status check `ACTIVE | CLOSED | EXPIRED`. The token itself is never stored.
- `permission_rules`: the D-06 matrix as data. `NULL` module or action is a wildcard; a rule
  applies when the employee's `job_titles.grade_id ≥ min_grade_id`. Rows 1–5 reproduce
  `has_permission` exactly (grade ≥ 8 all; grade ≥ 5 VIEW; everyone LEAVE CREATE/VIEW and
  EMPLOYEE VIEW). Authorities are `MODULE:ACTION`, checked with `@PreAuthorize`.

**Behaviour decisions implemented.**
- Login: `lower(trim(username))` vs `lower(email)`, `employment_status = 'ACTIVE'`,
  `MIN(emp_id)` (OQ-06; note this also matches a soft-deleted row that is still ACTIVE, which
  is legacy parity). Unknown user, wrong password, missing credential, locked credential and
  inactive employee give the same 401 `INVALID_CREDENTIALS` (-20301); a BCrypt comparison runs
  against a dummy hash when there is no credential, so timing is uniform.
- Lockout (OQ-07): the 5th consecutive failure sets `locked_until = now + 15 min`; while locked
  even the right password fails; the counter resets on success and when a lock has expired.
  Rows are locked `FOR UPDATE`, so concurrent failures are counted.
- Sessions (OQ-08): `expires_at = login_time + 30 min`, absolute. The first request at or after
  `expires_at` marks the row `EXPIRED`. Logout marks it `CLOSED` and sets `logout_time`. A
  session also stops working when its employee is no longer ACTIVE. Settings:
  `hrms.auth.session-timeout`, `max-failed-attempts`, `lock-duration`.
- Change password (PKG-S02): legacy policy order and codes (-20310 length ≥ 8, -20311 uppercase,
  -20312 digit; all `PASSWORD_POLICY` 400) plus a 72-byte cap (BCrypt limit). A wrong current
  password is 422 `INVALID_CURRENT_PASSWORD` (A-P2-01). Success re-hashes, resets lockout and
  closes the caller's **other** sessions.
- Audit (D-08): login (`USER_SESSIONS` INSERT), logout (`USER_SESSIONS` UPDATE) and change
  password (`USER_CREDENTIALS` UPDATE, no hash or password in the payload) write `audit_log` in
  the same transaction. Failed logins are not audited row-by-row; the counter is the record.
- Menu (HRMS_MENU backend): Employee, Leave, Performance always enabled (FRM-014 parity),
  Payroll needs `PAYROLL:VIEW`, Reports needs `REPORTS:VIEW`; System Admin is not returned
  (OQ-03); account entries Change Password and Logout. Navigation targets stay with P7.
- Employee read model: id, number, names, email, status, type, `active`, hire date, manager id
  and name, location code and name, department, job title. No SSN, birth date, address or pay.
  Default filter `employment_status = 'ACTIVE' AND active_flag`; `includeInactive=true` lifts it.
  Search is fully parameterized: case-insensitive name **prefix** (`starts_with`, so `%`/`_` are
  literal), exact dept/manager/status/location, hire-date range, paging (max 200), ordered by
  last name, first name, emp_id. `EmployeeReadService.isActive` is the in-process read API that
  replaces `PKG_EMPLOYEE.is_active` for other modules (OQ-01).
- Times: the service clock is an injected `java.time.Clock` (UTC), truncated to microseconds to
  match PostgreSQL precision.

**Not done in P2 (by design).** No credential provisioning endpoint or dev-seed passwords (OQ-15).
No SSN encryption (`encrypt_ssn`/`decrypt_ssn` move to P5 writes / P8 re-encrypt; the read model
never reads SSN). No React frontend (optional in P2). No row-level scoping (OQ-09 scoping
applies to the leave/performance/payroll routes of P3–P6).

### 4.3 P3 notes (leave pilot + contract freeze)

**Code.** `com.hrms.leave` (`LeaveService`, `LeaveAccrualService`, `BusinessDays`, `LeaveController`,
`LeaveModels`), `com.hrms.notification` (`NotificationService` outbox writer, `NotificationDispatcher`,
`NotificationSender` port with a log-only default), `com.hrms.common.ScheduledJobs` (accrual cron on the 1st
and the queue every 5 minutes; off unless `hrms.jobs.enabled=true`).

**Migration V10.** `leave_accrual_runs` (one row per month, `UNIQUE (accrual_period)`), FK + unique
`(run_id, emp_id, leave_type_id)` on `leave_accrual_log`, index `leave_requests (emp_id, status, start_date)`.

**Behaviour (parity unless marked fix).**
- Submit: the PKG-L01 check order and codes; the caller can only submit for themself (no on-behalf path; FRM-050).
  Concurrency: a per-employee advisory transaction lock serializes overlap/balance checks (legacy had none).
- Approve/reject: the assigned approver or a `LEAVE:APPROVE` holder; never the requester (403, new). The approver
  column is overwritten with the decider, as legacy did. Cancel: owner or `LEAVE:ADMIN` (managers cannot cancel).
  Rows the caller cannot see are 404 (OQ-09), so ids are not probeable.
- Notifications: same recipients and texts as PKG_LEAVE (submit → approver, approve/reject → employee, none on
  cancel). The outbox row is written **in the business transaction** (fix: legacy autonomous transaction could
  notify for a rolled-back request). Delivery is at-least-once; `retry_count` cap 3; SMTP adapter is P8 cutover
  work (only the `NotificationSender` port exists).
- Audit (D-08): `INSERT` on submit, `STATUS_CHANGE {status}` old→new on every transition, same transaction.
- Accrual: legacy rules (ACTIVE employees, active MONTHLY types, tenure on the accrual date, MAX_BALANCE cap vs
  `available`, balances initialized), but idempotent per month and one transaction (fix: legacy committed every
  100 rows and accrued again on every re-run).
- `cancelPendingRequestsOf(empId, reason, actor)` is the P5 terminate entry point for DEF-22.

**Not done in P3 (by design).** Carryover/expiry (PKG-L05, DEF-03) — deferred to P8 per OQ-12; the
`leave_accrual_runs` pattern is the template for its idempotency record. No React pilot UI (optional, OQ-16).
No SMTP adapter. `TAKEN` status transition (legacy had no code path setting it) — unchanged.

### 4.4 P4 notes (performance)

**Code.** `com.hrms.performance` (`PerformanceService`, `PerformanceController`, `PerformanceModels`). Replaces
every PKG_PERFORMANCE routine except the public `create_review`, which is internal to review generation
(the form never called it; reviews are created only by `POST …/cycles/{cycleId}/reviews/generate`).

**Migration V200** (coordinator-reserved range V200–V299, A-P4-02). `UNIQUE (cycle_id, emp_id, review_type)`
on `performance_reviews` (legacy `generate_reviews_for_cycle` caught `DUP_VAL_ON_INDEX`, but no unique key existed,
so re-runs duplicated reviews; the P8 data rehearsal must dedupe before loading). `NOT VALID` checks
`progress_pct` and `weight_pct` 0–100 and `end_date >= start_date` (new rows checked, legacy rows still load).

**Authorization (re-homed Forms DML policy).** HRMS_PERFORMANCE checked only the session (FRM-076), so
every route needs only authentication except: cycle create/open/close and review generation
→ `PERFORMANCE:ADMIN` (FRM-071); the HR views `GET …/cycles/{id}/reviews` and `…/rating-distribution` →
`PERFORMANCE:VIEW`. Row scope (OQ-09): a review and its goals are read by the reviewed employee, the assigned
reviewer and `PERFORMANCE:VIEW` holders (anyone else 404); self-assessment and acknowledge only by the
employee, the manager review only by the reviewer, goal add/progress by either (another party 403). No
generic update route exists, so `STATUS` changes only through commands (FRM-073). `DELETE` on cycles,
reviews and goals is mapped and always returns 405 `DELETE_FORBIDDEN` (-20504) (FRM-071/072/074, D-03).

**State machines (DEF-21 fixed per OQ-04).** Cycle: DRAFT → OPEN (else -20401); close needs OPEN (legacy
`IN_PROGRESS`/`CALIBRATION`, which no code sets, also close), else -20401. Review: self-assessment needs
NOT_STARTED/SELF_REVIEW (-20402, unchanged); manager review needs MANAGER_REVIEW/MEETING_SCHEDULED (-20402,
**new**); acknowledge needs COMPLETED and now returns 409 -20402 instead of a silent no-op. Rating must be
1.0–5.0 (-20403) and have at most one decimal (also -20403; `NUMERIC(2,1)` would silently round). Goal progress
0–100 (400 `VALIDATION_FAILED`, **new**); status derivation and comment retention are ported as-is (explicit
status wins; ≥100 COMPLETED; >0 IN_PROGRESS; 0 keeps the status).

**Parity kept on purpose.** No cycle-status gate on review/goal writes or review generation (OQ-P4-1);
reviewer = the employee's manager at generation time, `employment_status = 'ACTIVE'` only (as legacy); the
three legacy notification subjects/bodies are unchanged and go through the P3 outbox. Audit: `REVIEW_CYCLES`
INSERT/STATUS_CHANGE, `PERFORMANCE_REVIEWS` INSERT/STATUS_CHANGE (with rating and label), `PERFORMANCE_GOALS`
INSERT/UPDATE — the legacy package audited only cycle creation.

**Not done in P4.** No React UI. Calibration (`calibrated_rating`, `calibration_notes`) and goal
`self_rating`/`manager_rating` have no legacy code path — not exposed. Reviewer reassignment — none in legacy.

---

## 5. Re-homed logic registry

### 5.0 Conventions

- **ID prefixes:**
  - `TRG-` DB trigger rule
  - `FRM-` Oracle Forms trigger, property or gate
  - `PLL-` Forms library routine
  - `MNU-` menu item or gate
  - `PKG-` package rule or batch job that must be re-homed
  - `VW-` view
  - `DEF-` documented or discovered defect that needs a port-vs-fix decision

  IDs are stable. Never renumber them. Add new IDs at the end of each table.
- **Dest:** the phase that owns re-homing, plus the target layer: `api-authz`, `service`, `domain`,
  `db-constraint`, `db-view`, `job`, `ui-only`.
- **Status:**
  - `open`: not yet done.
  - `in-progress`.
  - `re-homed (Pn, <sha/test>)`.
  - `drop-proposed`: a UI-only item with a rationale. P8 confirms it.
  - `dropped (Pn, rationale)`.
  - `blocked (OQ-xx)`.
- P0 set every entry to `open` or `drop-proposed`. Line numbers refer to `main@de01258`.

### 5.1 Database triggers (`plsql/triggers/`): 6 triggers, 11 rules

| ID | Trigger (file) | Rule | Legacy code | Dest | Status |
|---|---|---|---|---|---|
| TRG-001 | TRG_EMP_BEFORE_INSERT (trg_employees.sql) | Default `CREATED_BY := USER` and `CREATED_DATE := SYSDATE` | – | P5 service (D-04) | open |
| TRG-002 | TRG_EMP_BEFORE_INSERT | Default `ACTIVE_FLAG='Y'` and `EMPLOYMENT_STATUS='ACTIVE'` | – | P1 column defaults + P5 service | in-progress (P1: column defaults `active_flag DEFAULT true`, `employment_status DEFAULT 'ACTIVE'` in V2; P5 service remains) |
| TRG-003 | TRG_EMP_BEFORE_INSERT | `HIRE_DATE ≤ SYSDATE + 180` | -20501 | P5 domain. Conflicts with FRM-035 (+90); see OQ-10 | open |
| TRG-004 | TRG_EMP_BEFORE_INSERT | Email unique, case-insensitive, among `ACTIVE_FLAG='Y'` rows (no DB unique constraint exists) | -20502 | P1 partial unique index `lower(email) WHERE active_flag` + P5 domain (409) | in-progress (P1: partial unique index `uq_employees_email_active` in V6, test `activeEmailIsUniqueCaseInsensitively`; P5 domain 409 remains) |
| TRG-005 | TRG_EMP_BEFORE_UPDATE | `MODIFIED_BY := NVL(:NEW, USER)` and `MODIFIED_DATE := SYSDATE` | – | P5 service (D-04) | open |
| TRG-006 | TRG_EMP_BEFORE_UPDATE | Block the `TERMINATED → ACTIVE` transition, except through rehire | -20503 | P5 domain state machine. Note DEF-13 | open |
| TRG-007 | TRG_EMP_BEFORE_UPDATE | Insert `EMPLOYEE_HISTORY` on change of status, `DEPT_ID` or `JOB_ID` | – | P5 service (explicit history writes, merged with PKG-E04). Note DEF-14 | open |
| TRG-008 | TRG_EMP_INSTEAD_OF_DELETE (actually `BEFORE DELETE`) | Forbid physical delete | -20504 | P1 grants (no DELETE) + P5 (no DELETE endpoint → 405), D-03 | in-progress (P1: V8 grants no DELETE/TRUNCATE to `hrms_app`, test `appRoleCannotDeleteAnything`; P5 405 remains) |
| TRG-009 | TRG_SALARY_AUDIT (trg_audit.sql) | JSON CDC on SALARY_RECORDS insert, update and delete → `PKG_AUDIT.log_action` | – | P6 service audit (D-08). Note DEF-20 (double audit) | open |
| TRG-010 | TRG_LEAVE_REQUEST_AUDIT | JSON CDC `{status}` old→new on `UPDATE OF STATUS` | – | P3 service audit (D-08). Note DEF-15 | done (P3: `LeaveService` writes `STATUS_CHANGE` old→new in the same transaction; DEF-15 fixed) |
| TRG-011 | TRG_DEPARTMENT_AUDIT | Audit insert, update and delete on DEPARTMENTS (action only, actor `USER`) | – | P5 service audit (D-08) | open |

### 5.2 Oracle Forms rules (`forms/xml-exports/*.xml`)

**HRMS_LOGIN**

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-001 | BTN_LOGIN requires USERNAME and PASSWORD, then calls `PKG_SECURITY.authenticate(user, pwd, CLIENT_HOST)` | P2 api (login, 400/401) | re-homed (P2, `POST /api/v1/auth/login`; `AuthApiTest.loginValidatesRequiredFields`, `loginIssuesOpaqueTokenAndStoresOnlyItsHash`) |
| FRM-002 | After login: `:GLOBAL.session_id`, `current_user` and `current_emp_id` are set (a second lookup by email, `ROWNUM=1`), then HRMS_MENU opens | P2 token claims and a "current principal" endpoint | re-homed (P2, login response `user` + `GET /api/v1/auth/me`; `PermissionMatrixTest.meReportsPermissionsGroupedByModule`) |
| FRM-003 | `WHEN OTHERS` turns every failure into "Invalid username or password" | P2. Uniform 401 for credential failures only. Other errors are not masked | re-homed (P2, uniform 401 `INVALID_CREDENTIALS`; `AuthApiTest.unknownUserBadPasswordAndInactiveEmployeeAreIndistinguishable`) |
| FRM-004 | WNFI and KEY-NEXT-ITEM focus and navigation | P2 ui-only | drop-proposed (React UX) |

**HRMS_MENU** (form; `HRMS_MENU.mmb.sql` is tracked as MNU-)

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-010 | WNFI disables MI_PAYROLL unless `has_permission(PAYROLL,VIEW)`, and BTN_PAYROLL denies the same | P2 matrix + P7 navigation (`api-authz`) | in-progress (P2: matrix + `GET /api/v1/menu` PAYROLL gate, `PermissionMatrixTest.menuFollowsTheLegacyGates`; P7 navigation remains) |
| FRM-011 | WNFI disables MI_ADMIN unless `has_permission(ADMIN,VIEW)`; target form HRMS_ADMIN does not exist | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| FRM-012 | WNFI disables MI_REPORTS and BTN_REPORTS unless `has_permission(REPORTS,VIEW)`; target form HRMS_REPORTS does not exist | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| FRM-013 | BTN_LOGOUT and MI_LOGOUT call `PKG_SECURITY.logout(:GLOBAL.session_id)` | P2 api (revoke token) | re-homed (P2, `POST /api/v1/auth/logout`; `AuthApiTest.logoutRevokesTheSession`) |
| FRM-014 | BTN_EMPLOYEES, BTN_LEAVE and BTN_PERFORMANCE open forms with **no** permission check | P7 navigation (entries shown to all authenticated users, which is parity) | in-progress (P2: menu entries enabled for every authenticated user, `menuFollowsTheLegacyGates`; P7 navigation remains) |
| FRM-015 | MI_CHANGE_PWD opens the change-password window | P2 (change-password endpoint, D-06) | re-homed (P2, `POST /api/v1/auth/change-password`; `ChangePasswordTest`) |
| FRM-016 | MI_ABOUT shows a version message | P7 ui-only | drop-proposed |

**Session check repeated in every form** (HRMS_EMPLOYEE, HRMS_LEAVE, HRMS_PAYROLL, HRMS_PERFORMANCE: WNFI)

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-020 | `IF NOT PKG_SECURITY.is_session_valid(...) THEN RAISE FORM_TRIGGER_FAILURE`. Note DEF-18: the forms pass `GET_APPLICATION_PROPERTY(USERNAME)`, not `:GLOBAL.session_id` | P2 token-validation filter on all `/api/**` (401) | re-homed (P2, `BearerTokenFilter` + `SecurityConfig` on all `/api/**`; `AuthApiTest.protectedRoutesRequireValidBearerToken`) |

**HRMS_EMPLOYEE**

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-030 | WNFI: without `has_permission(EMPLOYEE,EDIT)`, the EMPLOYEE block gets `INSERT_ALLOWED`, `UPDATE_ALLOWED` and `DELETE_ALLOWED` = false | P5 api-authz (`EMPLOYEE:EDIT` on create, update, terminate, rehire, transfer, promote). EDIT maps to the D-06 actions | open |
| FRM-031 | WNFI: `DEFAULT_WHERE` = `EMPLOYMENT_STATUS='ACTIVE' AND ACTIVE_FLAG='Y'` | P2 read model default filter (D-03) | re-homed (P2, `EmployeeReadService` default filter; `EmployeeReadApiTest.defaultReadsAreActiveOnly`) |
| FRM-032 | PRE-INSERT sets `EMP_ID := SEQ_EMPLOYEE.NEXTVAL`, `EMP_NUMBER := generate_emp_number`, `ACTIVE_FLAG`, status `ACTIVE`, `CREATED_BY := :GLOBAL.current_user` | P5 service (D-02, D-04) | open |
| FRM-033 | PRE-UPDATE sets `MODIFIED_BY` and `MODIFIED_DATE` | P5 service (D-04) | open |
| FRM-034 | WVI EMAIL: `PKG_VALIDATION.validate_email_format` (the server regex, **not** the PLL) | P5 domain validation. Note DEF-04 | open |
| FRM-035 | WVI HIRE_DATE: `> SYSDATE + 90` rejected | P5 domain. Conflicts with TRG-003 (+180); see OQ-10 | open (unblocked: OQ-10 closed, user accepted 180 days) |
| FRM-036 | WVI DEPT_ID must be an active department | P5 domain (= validate_dept -20003) | open |
| FRM-037 | WVI JOB_ID must be an active job | P5 domain (-20011) | open |
| FRM-038 | Items EMP_ID and EMP_NUMBER are not insertable or updatable; CREATED_BY and CREATED_DATE are not updatable | P5 api (read-only fields; ignored or rejected on write) | open |
| FRM-039 | SALARY block is read-only (no I/U/D) | P6 (salary changes only through salary-record operations) | open |
| FRM-040 | LOV_MANAGERS lists ACTIVE employees only | P5 domain (validate_manager -20004) + P2 lookup endpoint | in-progress (P2: active-only `GET /api/v1/employees` serves the manager LOV; P5 validate_manager remains) |
| FRM-041 | The EMPLOYEE block does **direct table DML** on EMPLOYEES, bypassing `PKG_EMPLOYEE.create_employee`, so it writes no salary, history or notification | P5. All writes go through the service. The direct-DML path is dropped | open |
| FRM-042 | Block `DeleteAllowed="Yes"` contradicts TRG-008; ALT_CONFIRM_DELETE alert | P5. Dropped by D-03 (no delete) | drop-proposed |
| FRM-043 | POST-QUERY looks up dept name, job title and manager name | P2 read model (joined fields) | re-homed (P2, joined `managerName`/`deptName`/`jobTitle`/`locationName`; `EmployeeReadApiTest.getReturnsTheReadModel`) |
| FRM-044 | ON-ERROR (FRM-40401/FRM-40501 messages) and KEY-EXIT confirm-on-unsaved | ui-only. The D-05 error envelope replaces it | drop-proposed |
| FRM-045 | Salary outside the grade range is a **soft warning with a manager override** (described in a PKG_EMPLOYEE.pkb comment; no such trigger exists in the export) | P6 (OQ-11) | open (unblocked: OQ-11 closed, user accepted soft warning) |
| FRM-046 | WNFI POPULATE_GROUP for RG_DEPARTMENTS, RG_JOB_TITLES and RG_LOCATIONS | P2/P5 lookup endpoints (active-only) | re-homed (P2, `GET /api/v1/lookups/{departments,job-titles,locations}` active-only; `EmployeeReadApiTest.lookupsAreActiveOnly`) |

**HRMS_LEAVE**

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-050 | WNFI `DEFAULT_WHERE` = `EMP_ID = :GLOBAL.current_emp_id` (self-only list; built by string concatenation) | P3 api-authz (row scoping: own requests; managers see approvals assigned to them) | done (P3: lists are self-only; detail visible to owner, approver, `LEAVE:APPROVE`; else 404) |
| FRM-051 | BTN cancel gate: only `PENDING`/`APPROVED`, then ALT_CONFIRM_CANCEL | P3 domain. **The server already enforces this** (`cancel_leave_request` raises -20204, PKG_LEAVE.pkb L331). It stays server-side | done (P3: `POST …/cancel` → 409 `INVALID_REQUEST_STATUS` unless PENDING/APPROVED) |
| FRM-052 | NEW_REQUEST requires leave type, start date and end date | P3 api validation (400) | done (P3: bean validation → 400 `VALIDATION_FAILED`) |
| FRM-053 | LEAVE_REQUEST and LEAVE_BALANCE blocks are read-only (no I/U/D) | P3 (no generic CRUD; only submit, approve, reject and cancel commands) | done (P3: command routes only; no PUT/PATCH/DELETE) |
| FRM-054 | POST-QUERY looks up the leave type name; RG_LEAVE_TYPES LOV | P3 read model and lookup | done (P3: `GET /api/v1/leave/types`; names in `LeaveRequest`) |
| FRM-055 | Submit passes `HALF_DAY_FLAG` but **never** `HALF_DAY_PERIOD` | P3 (accept `halfDayPeriod`; see DEF-01) | done (P3: `halfDayPeriod` accepted and required for half days) |
| FRM-056 | Tabs for pending approvals and team calendar (`get_pending_requests`, `get_team_calendar`) | P3 api | done (P3: `GET /api/v1/leave/approvals`, `GET /api/v1/leave/team-calendar`) |

**HRMS_PAYROLL**

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-060 | WNFI: without `has_permission(PAYROLL,VIEW)` the form exits | P6 api-authz (`PAYROLL:VIEW` on all payroll reads) | open |
| FRM-061 | WNFI `DEFAULT_WHERE` on PAY_PERIOD = `STATUS='OPEN'` | P6 list default filter | open |
| FRM-062 | **BTN_CALCULATE gate: `PAYROLL_RUN.STATUS = 'PENDING'` only.** This is Forms-only. Verified: `PKG_PAYROLL.calculate_payroll` has **no** status check (L271-347) | P6 domain (409 `INVALID_RUN_STATUS`) | open |
| FRM-063 | BTN_APPROVE gate: `has_permission(PAYROLL,APPROVE)`. This is Forms-only: `approve_payroll` checks status (CALCULATED, -20103) but not permission | P6 api-authz (`PAYROLL:APPROVE`) | open |
| FRM-064 | BTN_CREATE_RUN calls `create_payroll_run` with **no** permission check beyond the form-entry VIEW check | P6 api-authz (recommended: `PAYROLL:CREATE`; see OQ-09) | open |
| FRM-065 | All PAY_PERIOD and PAYROLL_RUN blocks are read-only | P6 (no generic CRUD) | open |

**HRMS_PERFORMANCE**

| ID | Rule | Dest | Status |
|---|---|---|---|
| FRM-070 | WNFI `DEFAULT_WHERE` on REVIEW_CYCLE = `STATUS IN ('OPEN','DRAFT')` | P4 list default filter | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: `GET /performance/cycles` defaults to OPEN+DRAFT ordered by year desc; `includeClosed=true` lists all) |
| FRM-071 | REVIEW_CYCLE block is read-only (no I/U/D); cycles are not editable from the form | P4 api-authz (only the lifecycle commands create, open and close, behind `PERFORMANCE:ADMIN`) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: cycles change only via create/open/close behind `PERFORMANCE:ADMIN`; others 403; `DELETE` 405 `DELETE_FORBIDDEN`) |
| FRM-072 | PERFORMANCE_REVIEW block: `InsertAllowed=No`, `DeleteAllowed=No`, `UpdateAllowed=Yes` | P4 (reviews are created only by `generate_reviews_for_cycle`/`create_review`; no delete) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: no review insert/update route; reviews come from `…/reviews/generate` and change only via workflow commands; `DELETE` 405) |
| FRM-073 | PERFORMANCE_REVIEW.STATUS item `UpdateAllowed=No` | P4 domain (status changes only through workflow commands) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: status changes only via self-assessment / manager-review / acknowledge commands) |
| FRM-074 | PERFORMANCE_GOAL block: `DeleteAllowed=No` (no goal deletes) | P4 (no DELETE; 405) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: `DELETE /performance/goals/{goalId}` → 405 `DELETE_FORBIDDEN` (-20504)) |
| FRM-075 | PERFORMANCE_GOAL insert and update are allowed | P4 api (add goal, update progress) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: `POST …/reviews/{id}/goals` and `POST …/goals/{id}/progress`) |
| FRM-076 | The form has **no** permission check (session check only), so any authenticated user can update reviews and goals | P4 api-authz (OQ-09: row scoping of reviewer, employee and HR) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: OQ-09 row scope: employee/reviewer write, + `PERFORMANCE:VIEW` read; strangers 404, wrong party 403) |
| FRM-077 | POST-QUERY looks up the employee name | P4 read model | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: `employeeName`, `reviewerName`, `jobTitle`, `deptName` on `PerformanceReview`) |

### 5.3 Menu module (`forms/menus/HRMS_MENU.mmb.sql`)

| ID | Rule | Dest | Status |
|---|---|---|---|
| MNU-001 | Modules → Employee, Payroll, Leave and Performance open their forms | P7 navigation endpoint and React routes | open |
| MNU-002 | Modules → Reports & Analytics (`HRMS_REPORTS`) is a **dead target** | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| MNU-003 | Modules → System Admin (`HRMS_ADMIN`) is a **dead target** | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| MNU-004 | Admin → System Parameters and User Management "requires ADMIN permission" | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| MNU-005 | Admin → Change Password | P2 (= FRM-015) | re-homed (P2, = FRM-015; menu `CHANGE_PASSWORD` entry) |
| MNU-006 | File, Edit, Query, Navigate and Help items (COMMIT_FORM, record navigation, Print via RUN_PRODUCT) | ui-only | drop-proposed |
| MNU-007 | "Menu items are enabled/disabled at runtime based on `has_permission`" | P7 navigation response filtered by the matrix (D-06) | in-progress (P2: `GET /api/v1/menu` filtered by the matrix; P7 navigation remains) |

### 5.4 PLL libraries (`forms/libraries/`)

Verified: **no exported form XML calls any PLL routine.** The forms attach the libraries, and the
EMPLOYEE form validates email through the server `PKG_VALIDATION`. The PLL rules are re-homed anyway
so that they are not lost.

| ID | Routine (library) | Behaviour | Dest | Status |
|---|---|---|---|---|
| PLL-001 | `validate_email` (HRMS_VALIDATION_LIB) | NULL is valid. Requires `@` not first or last, and a `.` after `@` that is not adjacent to it or last. The comment says it "rejects subdomains" but the **code accepts them**. It has no TLD or whitespace check. | P5 domain: a single server-side email validator (DEF-04) | open |
| PLL-002 | `validate_phone` | NULL is valid. Strips `()-. +x`, then requires 10 or 11 digits | P5 domain (aligned with `PKG_COMMON.is_valid_phone`) | open |
| PLL-003 | `validate_ssn` | NULL is valid. 9 digits; no `000`, `00` or `0000` group. **Stricter than** server `PKG_COMMON.is_valid_ssn`, which checks 9 digits only | P5 domain (recommended: adopt the stricter rule; OQ-04) | open |
| PLL-004 | `validate_date_not_future` | NULL is valid. `TRUNC(date) ≤ TRUNC(SYSDATE)` | P5 domain (DATE_OF_BIRTH; injected Clock) | open |
| PLL-005 | `validate_salary_range` | Checks salary against `JOB_GRADES.MIN/MAX` and returns a message (not an error). "Cached" in the comment, but it is a direct query | P6 domain (soft warning; see FRM-045 and OQ-11) | open |
| PLL-010 | `handle_error` (HRMS_COMMON_LIB) | Logs through `PKG_COMMON.log_error`, shows MESSAGE twice, raises FORM_TRIGGER_FAILURE | Dropped. Replaced by server-side error logging and D-05 | drop-proposed |
| PLL-011 | `toolbar_save/clear/query/first/prev/next/last/insert/delete/exit` (10) | Forms built-ins | ui-only | drop-proposed |
| PLL-012 | `format_date` (`MM/DD/YYYY`), `format_datetime` | Display formatting | ui-only. The API uses ISO-8601; React formats | drop-proposed |
| PLL-013 | `get_current_user` (`NVL(:GLOBAL.current_user, USER)`) | Principal lookup | P2 (authenticated principal) | re-homed (P2, `HrmsPrincipal` via `@AuthenticationPrincipal`; actor = login email) |
| PLL-014 | `get_session_id`, `check_session` (`is_session_valid`) | Session gate | P2 (= FRM-020) | re-homed (P2, = FRM-020) |
| PLL-015 | `refresh_lov` | Repopulates the record group | ui-only (lookup endpoints) | drop-proposed |

### 5.5 Package rules and batch jobs that must be re-homed

Full API and signature catalogue: `inventory.md` §I-3. This table lists the rules that cross the
re-homing boundary.

| ID | Package.routine | Rule summary | Dest | Status |
|---|---|---|---|---|
| PKG-E01 | PKG_EMPLOYEE.generate_emp_number | `MAX()+1` race with a sequence fallback | P5 (D-02) | in-progress (P1: `hrms.seq_emp_number` START 1000 in V2; P5 generator remains) |
| PKG-E02 | PKG_EMPLOYEE.validate_dept / validate_manager | Active dept (-20003). Manager ACTIVE (-20004). Cycle walk up to depth 15 (`c_max_hierarchy_depth`) (-20004); cycle check only when `p_emp_id` is not null | P5 domain | open |
| PKG-E03 | PKG_EMPLOYEE.create_employee | Names required (-20010), dept, manager, job active (-20011). Salary vs grade is only a debug warning. Location defaults from dept. Names UPPER/TRIM, email lower/TRIM. Calls **PKG_PAYROLL.create_salary_record** (NEW_HIRE). HIRE history, audit, welcome and manager notifications | P5 service. Salary goes through the interface (OQ-01) | open |
| PKG-E04 | PKG_EMPLOYEE.log_history | `AUTONOMOUS_TRANSACTION` + COMMIT; swallows errors | P5 (same transaction; errors propagate) | open |
| PKG-E05 | PKG_EMPLOYEE.update_employee / transfer / promote / terminate / rehire | See inventory §I-3.1. Terminate auto-cancels PENDING leave and end-dates salary and pay elements. Promote calls create_salary_record (PROMOTION). Rehire calls create_salary_record (REHIRE) | P5 (salary through the interface) | open |
| PKG-E06 | PKG_EMPLOYEE.search_employees | Dynamic SQL with concatenation (SQL injection) | P2 read model (parameterized), DEF-09 | re-homed (P2, `EmployeeReadService.search`, bind parameters only; `EmployeeReadApiTest.searchIsInjectionSafe` (DEF-09 fixed)) |
| PKG-E07 | PKG_EMPLOYEE.get_org_chart / VW_ORG_HIERARCHY | CONNECT BY, active only, depth ≤ 10 | P7 (recursive CTE) | open |
| PKG-E08 | PKG_EMPLOYEE.set_session_context (called by PKG_SECURITY) | Package globals for user, emp and dept | P2. Replaced by the request-scoped principal | re-homed (P2, request-scoped `HrmsPrincipal`; no package/global state) |
| PKG-L01 | PKG_LEAVE.submit_leave_request | -20001, -20203 (type, tenure), -20210, -20211 (backdating > 5 days), business days (-20212), overlap (-20202), balance when ACCRUAL_FLAG='Y' (-20201), status PENDING/APPROVED by REQUIRES_APPROVAL, PENDING balance increment, manager notification, audit | P3 | done (P3: `LeaveService.submit`; DEF-01, DEF-12 fixed) |
| PKG-L02 | PKG_LEAVE.approve / reject / cancel | PENDING only for approve and reject; PENDING or APPROVED for cancel (-20204). Balance moves PENDING→USED, PENDING release, or USED restore. Notifications on approve and reject (none on cancel) | P3 | done (P3: approve/reject/cancel; outbox notifications; status audit) |
| PKG-L03 | PKG_LEAVE.calculate_business_days | Weekdays minus HOLIDAYS (active, `LOCATION_CODE IS NULL OR = emp location`) | P3 domain (DEF-02) | done (P3: `BusinessDays`; DEF-02 via data) |
| PKG-L04 | PKG_LEAVE.run_monthly_accrual | DBMS_SCHEDULER on the 1st; MAX_BALANCE cap; tenure; commit every 100 | P3 or P8 `job` (Spring `@Scheduled` + idempotency); OQ-12 | done (P3: `LeaveAccrualService` + `POST /api/v1/leave/accrual-runs` + `ScheduledJobs`; idempotent per month) |
| PKG-L05 | PKG_LEAVE.process_carryover / expire_carryover | Year-end carryover capped at CARRYOVER_MAX, with expiry months | P3 or P8 `job` (DEF-03) | open — deferred to P8 per OQ-12 (DEF-03) |
| PKG-P01 | PKG_PAYROLL.create_salary_record | Salary > 0 (-20101). End-dates the prior active record at `effective-1`. Inserts ANNUAL. Audit | P6. Implements the P5 interface | open |
| PKG-P02 | PKG_PAYROLL.create_pay_periods | MONTHLY: end = LAST_DAY, pay = end, Saturday → Friday (−1), Sunday → Friday (−2). BIWEEKLY: first Friday of the year −13 = start, 14-day periods, pay = end + 5, name `YYYY-BW-NN` | P6 domain | open |
| PKG-P03 | PKG_PAYROLL.calculate_payroll / calculate_employee_pay | No status gate (FRM-062). Commits every 50 (DEF-16). Gross = annual / periods. Fed, state, FICA and Medicare. Deductions by element | P6 | open |
| PKG-P04 | PKG_PAYROLL.approve_payroll / reverse_payroll / close_pay_period / create_payroll_run | Approve needs CALCULATED (-20103). Reverse has **no** status check. Close rejects CLOSED (-20102). Create rejects CLOSED (-20102) | P6 domain | open |
| PKG-P05 | PKG_PAYROLL.calculate_federal_tax / state / fica / medicare | Hard-coded 2024 brackets and constants; flat state rates | P6 (DEF-05) | open |
| PKG-P06 | PKG_PAYROLL.generate_pay_register | UTL_FILE CSV | P7 report endpoint (CSV) or drop; OQ-03 | open |
| PKG-P07 | Payroll batch "DBMS_SCHEDULER" (header comment; no job DDL in the repo) | Batch calculate | P6 `job` (Spring scheduler; no DBMS_SCHEDULER) | open |
| PKG-F01 | PKG_PERFORMANCE cycle lifecycle | create → DRAFT; open needs DRAFT (-20401); **close has no status check** | P4 (DRAFT→OPEN→CLOSED enforced; DEF-21) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: DRAFT→OPEN→CLOSED, -20401 on any other transition; DEF-21 close fixed) |
| PKG-F02 | PKG_PERFORMANCE review workflow | NOT_STARTED/SELF_REVIEW → MANAGER_REVIEW (-20402). Manager review: rating 1.0–5.0 (-20403), label ≥4.5 Exceptional, ≥3.5 Exceeds Expectations, ≥2.5 Meets Expectations, ≥1.5 Needs Improvement, else Unsatisfactory → COMPLETED (**no status check**). Acknowledge requires COMPLETED (silent no-op otherwise) | P4 (DEF-21) | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: -20402 status gates incl. manager review and acknowledge (DEF-21), -20403 rating 1.0–5.0 one decimal, legacy label bands) |
| PKG-F03 | PKG_PERFORMANCE goals / generate_reviews_for_cycle | add_goal, update_goal_progress (≥100 → COMPLETED, >0 → IN_PROGRESS) with no validation; one review per ACTIVE employee that has a manager | P4 | re-homed (P4, `2d4d2094c777`, `PerformanceApiTest`: add goal / progress 0–100 (DEF-21) / generation idempotent via V200 unique key; `get_team_reviews` = `GET /performance/reviews?role=REVIEWER`, `get_rating_distribution` = `…/rating-distribution`) |
| PKG-S01 | PKG_SECURITY.authenticate / logout / is_session_valid / has_permission | See D-06 | P2 | re-homed (P2, `AuthService`, `PermissionService`, `permission_rules` (V9); `AuthApiTest`, `PermissionMatrixTest` (DEF-10 fixed)) |
| PKG-S02 | PKG_SECURITY.change_password | Policy: ≥8 characters (-20310), an uppercase letter (-20311), a digit (-20312). The body is a stub | P2 (implemented for real against user_credentials) | re-homed (P2, `AuthService.changePassword` against `user_credentials`; `ChangePasswordTest`) |
| PKG-S03 | PKG_SECURITY.encrypt_ssn / decrypt_ssn / hash_password | Hard-coded AES-256 key; MD5 | P2 (key from config) / P8 (re-encrypt), D-06 | in-progress (P2: `hash_password`/MD5 dropped for `DelegatingPasswordEncoder` BCrypt. `encrypt_ssn`/`decrypt_ssn` re-targeted to P5 (SSN writes, key from config) and P8 (re-encrypt): the P2 read model never reads SSN) |
| PKG-A01 | PKG_AUDIT.log_action | Autonomous transaction; swallows errors | P1 table + each phase's service (D-08) | in-progress (P1: `audit_log` with JSONB payloads and `STATUS_CHANGE` in V5; P2: `AuditWriter` (same transaction, failures propagate) for login, logout and change-password; each phase's service writes remain; P3: leave INSERT + STATUS_CHANGE) |
| PKG-A02 | PKG_AUDIT.purge_old_records (365 days) | Retention | P8 `job` (needs a D-03 exception amendment) | open |
| PKG-N01 | PKG_NOTIFICATION.send_notification / process_queue (DBMS_SCHEDULER every 5 minutes) / retry_failed / cancel | Queue table + UTL_MAIL | P3 (outbox, used first by leave) + `job` | done (P3: `NotificationService` transactional outbox + `NotificationDispatcher`; SMTP adapter at P8 cutover) |
| PKG-C01 | PKG_COMMON / PKG_VALIDATION helpers | Params, business days, fiscal year (Oct 1), formatting, validators | Each consuming phase; validators in P5 | open |
| PKG-R01 | PKG_REPORTING (7 reports + refresh_reporting_tables) | Headcount, compensation, turnover, new hires, leave utilization, payroll summary, EEO | P7 (OQ-03) | open (unblocked: OQ-03 closed, user accepted the default) |
| PKG-I01 | PKG_INTEGRATION (GL journal, benefits feed, T&A import, org sync) | UTL_FILE; FTP credentials in SYSTEM_PARAMETERS. **Not one of the program's 5 modules** | OQ-13 | drop-proposed (OQ-13 closed: out of scope for P1–P8, follow-up; the tables it reads are migrated in P1) |

### 5.6 Views (`schema/views/hrms_views.sql`)

| ID | View | Dest | Status |
|---|---|---|---|
| VW-001 | VW_ACTIVE_EMPLOYEES | P1 DB view + P2 read model | re-homed (P1: `hrms.vw_active_employees` in V7; P2: the read model applies the same filter in `EmployeeReadService`, `defaultReadsAreActiveOnly`) |
| VW-002 | VW_ORG_HIERARCHY (CONNECT BY) | P1 (recursive CTE) + P7 endpoint | in-progress (P1: recursive CTE `hrms.vw_org_hierarchy` in V7, test `orgHierarchyKeepsConnectBySemantics`; P7 endpoint remains) |
| VW-003 | VW_EMPLOYEE_COMPENSATION | P1 + P7 endpoint (`PAYROLL:VIEW`) | in-progress (P1: `hrms.vw_employee_compensation` in V7; P7 endpoint remains) |
| VW-004 | VW_LEAVE_SUMMARY | P1 + P7 endpoint | in-progress (P1: `hrms.vw_leave_summary` in V7, parity incl. DEF-23; P7 endpoint remains) |
| VW-005 | VW_PAYROLL_LATEST | P1 + P6/P7 | in-progress (P1: `hrms.vw_payroll_latest` in V7; P6/P7 remains) |
| VW-006 | VW_PENDING_APPROVALS | P1 + P7 endpoint | in-progress (P1: `hrms.vw_pending_approvals` in V7, test `reportingViewsAreQueryable`; P7 endpoint remains) |

### 5.7 Defects (port-vs-fix candidates; the decision belongs to the user, see OQ-04)

**Documented in the package headers or comments**

| ID | Defect | Evidence | Recommended default |
|---|---|---|---|
| DEF-01 | The half-day overlap check ignores `HALF_DAY_FLAG` and `HALF_DAY_PERIOD`, so AM and PM requests on the same day block each other. A half day is always 0.5, even when the range spans days, weekends or holidays | PKG_LEAVE.pkb L45-62, L129-131 | Fix: period-aware overlap; a half day requires `start = end` — **fixed in P3** (`LeaveApiTest.overlapIsHalfDayPeriodAware`) |
| DEF-02 | Observed holidays are not excluded: the check matches the exact date only | PKG_LEAVE.pkb L9-10 | Fix through **data**: HOLIDAYS carries an observed-date row. No algorithm change — **P3: no code needed** — holidays are matched by date + location; observed dates are P8 data-migration rows (test: HQ-only holiday not excluded for CHI) |
| DEF-03 | Carryover "double-expire". `expire_carryover` itself is idempotent, because it zeroes `CARRYOVER_FROM_PREV`. The real double path: re-running `process_carryover` resets `CARRYOVER_FROM_PREV`, then expiry subtracts into ADJUSTMENT again. Expiry also subtracts the full carryover even after it has been used | PKG_LEAVE.pkb L558-623 | Fix: an idempotency record per (year, employee, type), and expire only the unused remainder — **deferred to P8** with PKG-L05 (OQ-12) |
| DEF-04 | Email validation drift. The PLL accepts subdomains, despite its comment, and has no TLD check. The server regex `^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$` is used by the form | PLL L14-38; PKG_COMMON.pkb L265-268 | Fix: one server validator using the PKG_COMMON regex |
| DEF-05 | Hard-coded 2024 federal brackets, standard deductions (14600/29200), allowance 4300, SS wage base 168600, flat state rates | PKG_PAYROLL.pkb L7-14, L643-718 | Fix: read TAX_BRACKETS and a parameter table, seeded with the 2024 values for parity |
| DEF-06 | YTD issues. YTD keys on the `PERIOD_START_DATE` year (biweekly periods spanning a year boundary go to the wrong year) and on `STATUS='CALCULATED'` only. The payslip has YTD placeholders of 0 | PKG_PAYROLL.pkb L802-819, L784-785 | Fix (key on PAY_DATE year; include approved and paid runs) |
| DEF-07 | "Overtime does not account for holidays". **No overtime calculation exists in the repo**; there is only an `OVERTIME_ELIGIBLE` column and a T&A import stub | PKG_PAYROLL.pks L11 | Record as not applicable. Overtime is out of scope unless the user adds it |

**Discovered in P0** (each verified against the source)

| ID | Defect | Evidence | Recommended default |
|---|---|---|---|
| DEF-08 | `generate_emp_number` MAX()+1 race | PKG_EMPLOYEE.pkb L39-55 | Fix (D-02; already locked) |
| DEF-09 | SQL injection in `search_employees` | PKG_EMPLOYEE.pkb L445-499 | Fix (parameterized) |
| DEF-10 | `authenticate` never verifies a password; MD5 `hash_password` is unused | PKG_SECURITY.pkb | Fix (D-06; already locked) |
| DEF-11 | Hard-coded AES key | PKG_SECURITY.pkb `c_encryption_key` | Fix (D-06; already locked) |
| DEF-12 | **The leave auto-approve path always fails.** When `REQUIRES_APPROVAL='N'`, the row is inserted as `APPROVED`, then `approve_leave_request` is called, which raises -20204 because the status is not PENDING. PENDING was also incremented for a row that is already approved | PKG_LEAVE.pkb L170, L176-182, L199-202, L225 | Fix: insert APPROVED and move the days straight to USED — **fixed in P3** (`noApprovalTypesAreApprovedOnSubmitAndUsedDirectly`) |
| DEF-13 | **`rehire_employee` is blocked by its own trigger.** It updates TERMINATED→ACTIVE, which TRG-006 rejects with -20503. It also has no check that the employee is TERMINATED, and no manager or location handling | PKG_EMPLOYEE.pkb L750-793; trg_employees.sql L68-73 | Fix: rehire is the one allowed transition and requires TERMINATED |
| DEF-14 | **TRG_EMP_BEFORE_UPDATE history inserts cannot work.** They use the columns `HISTORY_ID`, `CHANGE_DATE`, `OLD_VALUE`, `NEW_VALUE`, `CHANGED_BY` and `CHANGE_REASON`, none of which exist in EMPLOYEE_HISTORY (the DDL has `HIST_ID`, `EFFECTIVE_DATE` and typed `OLD_*`/`NEW_*` columns). The values `DEPARTMENT_CHANGE` and `JOB_CHANGE` also violate `CHK_CHANGE_TYPE` | trg_employees.sql L77-110; 01_core_tables.sql L152-177 | Re-home as service writes in the `log_history` shape (P5) |
| DEF-15 | **Leave status audit is silently lost.** TRG_LEAVE_REQUEST_AUDIT writes `ACTION_TYPE='STATUS_CHANGE'`, but `CHK_AUDIT_ACTION` allows only INSERT, UPDATE and DELETE, and PKG_AUDIT's `WHEN OTHERS` swallows the violation | trg_audit.sql L56-68; 04_performance_tables.sql L104; PKG_AUDIT.pkb | Fix (D-08 adds STATUS_CHANGE; already locked) — **fixed in P3** (`approveMovesPendingToUsedAndAuditsTheStatus`) |
| DEF-16 | `calculate_payroll` commits every 50 employees, and the CALCULATING status is committed before the work, so a failure leaves a half-calculated run | PKG_PAYROLL.pkb L286-327 | Fix: one transaction, or idempotent per-employee resumable work |
| DEF-17 | `USER_SESSIONS.USERNAME` and every `CREATED_BY`/`MODIFIED_BY`/`CHANGED_BY` are `VARCHAR2(30)`, but the actor written is the email, up to 100 characters | 04_performance_tables.sql L156; PKG_SECURITY.pkb | Fix (D-04 widens; already locked) |
| DEF-18 | The Forms session check passes `GET_APPLICATION_PROPERTY(USERNAME)`, the DB user and not the session ID, to `is_session_valid` | HRMS_EMPLOYEE.xml L34; HRMS_LEAVE.xml, HRMS_PERFORMANCE.xml WNFI | Dropped with Forms (FRM-020 re-homed) |
| DEF-19 | The hire-date limit differs: Forms allows +90 days, the trigger +180 | HRMS_EMPLOYEE.xml L383; trg_employees.sql L35 | OQ-10 |
| DEF-20 | A SALARY_RECORDS insert is audited twice: by `create_salary_record` and by TRG_SALARY_AUDIT | PKG_PAYROLL.pkb L57; trg_audit.sql | Fix: one audit row with JSON (D-08) |
| DEF-21 | State checks are missing in `close_review_cycle` (any status → CLOSED), `submit_manager_review` (any status → COMPLETED), `reverse_payroll` (any status → REVERSED) and `acknowledge_review` (silent no-op). `update_goal_progress` does not validate the 0–100 range | PKG_PERFORMANCE.pkb, PKG_PAYROLL.pkb L583-600 | Fix: enforce state machines (P4 cycle DRAFT→OPEN→CLOSED is in the program text) — **P4 part fixed** (`close_review_cycle`, `submit_manager_review`, `acknowledge_review`, `update_goal_progress`; `2d4d2094c777`); `reverse_payroll` remains for P6 |
| DEF-22 | `LEAVE_REQUESTS` cancel never notifies; terminate's auto-cancel of PENDING leave does **not** release the PENDING balance | PKG_EMPLOYEE.pkb L667-683 | Fix: route through the leave cancel logic (P5 → P3 service) — P3 provides `LeaveService.cancelPendingRequestsOf`; P5 wires terminate to it |

---

**Discovered in P1**

| ID | Defect | Evidence | Recommended default |
|---|---|---|---|
| DEF-23 | `VW_LEAVE_SUMMARY.AVAILABLE` is `OPENING_BALANCE + ACCRUED - USED + ADJUSTMENT`, which **ignores PENDING**, while `LEAVE_BALANCES.AVAILABLE` subtracts it. The report can show more days than a request may use | schema/views/hrms_views.sql L96; 03_leave_tables.sql L47 | P1 keeps parity in the view. P7 endpoint returns both `pending` and the generated `available` (net of pending). See OQ-14 |
| DEF-24 | Oracle `CHECK (X IN ('A','B', NULL))` never rejects anything, because `X IN (…, NULL)` is UNKNOWN for a non-matching value and UNKNOWN passes a CHECK. `CHK_ACCRUAL_FREQ` and `CHK_HALF_DAY` were no-ops | 03_leave_tables.sql L31, L90 | Fix (already done by D-01's explicit `x IS NULL OR x IN (…)`). P8 data migration must report legacy rows that violate the tightened checks |
| DEF-25 | The employee seed header claims 25 employees; it inserts 24 | data/seed/02_employee_data.sql L4 | Informational; no action |

---

## 6. Open questions

Format: ID · question · findings · **recommended default** · status. The coordinator relays the
user's answers. If a phase is blocked without an answer, it implements the recommended default and
marks it **"provisional — awaiting user confirmation"**.

| ID | Question | P0 findings | Recommended default | Status |
|---|---|---|---|---|
| OQ-01 | How should the PKG_EMPLOYEE ↔ PKG_PAYROLL "circular dependency" be resolved? | **It is not circular in code.** Grepping `PKG_x.` usages shows `PKG_EMPLOYEE → PKG_PAYROLL.create_salary_record` (create L275, promote L617, rehire L778), while `PKG_PAYROLL` calls only `PKG_AUDIT` and `PKG_COMMON`. **There is no call to `PKG_EMPLOYEE.is_active` anywhere.** `is_active` (PKG_EMPLOYEE.pkb L885) is never called. `PKG_PAYROLL` reads `EMPLOYEES` directly (L298-300, L787, L862). The "Circular dependency" notes in PKG_EMPLOYEE.pks L9, PKG_PAYROLL.pks L9 and the PKG_EMPLOYEE.pkb L273-274 comment are wrong. The header `Dependencies:` lists are wrong too: PKG_PAYROLL, PKG_LEAVE, PKG_PERFORMANCE, PKG_REPORTING and PKG_INTEGRATION claim to depend on PKG_EMPLOYEE, but none of them calls it. The only other real cross-module call is `PKG_SECURITY → PKG_EMPLOYEE.set_session_context`. Full call graph: inventory §I-2. | P5 defines a `SalaryRecordPort` (in-process Java interface, called synchronously in the same transaction, so hire, promote and rehire stay atomic). P5 ships a no-op or recording stub. P6 provides the real implementation. Payroll reads employee status through the employee module's read API or repository, never through a reverse service call. Async events or an outbox are deferred. | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-02 | Are there unverified server-side status checks in truncated package bodies? | **None of the package bodies in the repo is truncated.** All 11 `.pkb` files are complete and end with `END PKG_x; /`. Every status check is verified: present = leave approve/reject (PENDING), leave cancel (PENDING/APPROVED), approve_payroll (CALCULATED), close_pay_period and create_payroll_run (not CLOSED), open_review_cycle (DRAFT), submit_self_assessment (NOT_STARTED/SELF_REVIEW), acknowledge (COMPLETED, silent), terminate (not TERMINATED), transfer (ACTIVE). **Absent** = calculate_payroll (the Forms PENDING gate is the only guard), reverse_payroll, close_review_cycle, submit_manager_review, update_goal_progress, rehire_employee. | Treat the Forms gates as authoritative requirements and enforce every state machine server-side (DEF-21, FRM-062). | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-03 | What is the scope of the dead menu items HRMS_REPORTS and HRMS_ADMIN? | Neither form exists in `forms/xml-exports/`. A backend exists: PKG_REPORTING (7 reports) and SYSTEM_PARAMETERS + `PKG_COMMON.set_param`. The menu says Admin needs the ADMIN permission. | REPORTS: P7 exposes read-only endpoints for the 4 named views (VW_PENDING_APPROVALS, VW_EMPLOYEE_COMPENSATION, VW_LEAVE_SUMMARY, VW_ORG_HIERARCHY) behind `REPORTS:VIEW`. The other PKG_REPORTING reports and the pay register are deferred. ADMIN: out of scope, except change-password (P2). The menu entry is hidden. | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-04 | What is the port-vs-fix policy for documented bugs (DEF-01…07) and discovered bugs (DEF-12…22)? | §5.7 | Security bugs are already fixed by D-02, D-04, D-06 and D-08. For business-logic bugs: **fix**, as recommended per row in §5.7. Each fix gets a parity test that documents how it deviates from legacy. P8 records the final decision for each bug. | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-05 | Target stack | — | — | **closed by D-00 (user decision, 2026-11-20)** |
| OQ-06 | Two ACTIVE employees can share an email (uniqueness is enforced only among `ACTIVE_FLAG='Y'` rows, and only by a trigger). What should login do? | authenticate takes `MIN(emp_id)`; the login form takes `ROWNUM=1` | Port MIN(emp_id) in P2. TRG-004 and the P1 partial unique index then prevent new duplicates. P8 data migration reports any existing duplicates. | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-07 | Lockout policy numbers | Legacy has no lockout | 5 consecutive failures → 15-minute lock; counter resets on success | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-08 | Session timeout: absolute or sliding? | Legacy: 30 minutes absolute from LOGIN_TIME | Port 30 minutes absolute in P2 | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-09 | Row-level scoping and the finer permission matrix: own records, own department, manager/reviewer rights, PAYROLL CREATE, PERFORMANCE write | `has_permission` comments promise "edit own department / own records" but the code does not implement it. The Performance form has no permission check | Keep exact legacy matrix parity (D-06). Add only the minimal scoping that the forms implied: leave lists are self-only, approvals go to the assigned approver, a review is updated by its reviewer or employee, and payroll create and calculate need `PAYROLL:EDIT`/grade ≥ 8. Each addition is marked provisional. | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-10 | Hire-date future limit: 90 days (Forms) or 180 days (trigger)? | DEF-19 | 180 days (the DB trigger was the authoritative server rule) | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-11 | Salary outside the grade range: soft warning with a manager override, or a hard error? | Server code only prints a debug message. The PLL and PKG_VALIDATION return messages. The override flow is undocumented | Soft warning: the response carries a `warnings[]` entry and the save is not blocked. No override workflow | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-12 | Which phase owns the leave batch jobs (accrual, carryover, expiry)? | Not named in the program text | P3 implements accrual. Carryover and expiry go to P8, or P3 if time allows | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-13 | Is PKG_INTEGRATION (GL, benefits, T&A, org sync) in scope? | Not one of the 5 modules; flat files and FTP | Out of scope for P1–P8. Record it as a follow-up. Its tables are still migrated in P1 | closed — user accepted recommended default (2026-11-20, relayed by coordinator) |
| OQ-14 | DEF-23: should the leave-summary report's `available` keep excluding PENDING (legacy view) or match `leave_balances.available`? | Raised in P1 after the user's 2026-11-20 acceptance of OQ-01..OQ-13, so not covered by it | Keep parity in the DB view (done in P1). The P7 endpoint returns `pending` and the generated `available` (net of pending) and labels them explicitly | open (P7 decides only if unanswered; mark provisional) |
| OQ-15 | How do employees get their first password? Legacy never verified passwords, so there are no credentials to migrate | Raised in P2. `user_credentials` starts empty; nobody can log in until a row exists | Coordinator-applied default: **production** — the P8 data migration creates a credential per ACTIVE employee with an unusable hash and a one-time reset link (sent through the P3 outbox). **Dev/test only** — `db/seed/R__dev_seed_p3.sql` gives every seeded ACTIVE employee the password `Hrms#2026` (bcrypt); it is in `db/seed`, which Flyway loads only with the `dev` profile or in tests, never on the production path (`ProductionMigrationTest`). Documented in `backend/README.md` | open — provisional, awaiting user confirmation (dev half implemented in P3; production half is P8) |
| OQ-16 | Should the program build the optional React leave pilot UI (login, submit, my requests, approvals) before P4/P5? | P3 prioritized backend + v1 freeze; the API is complete for such a UI | Build it as a small follow-up on top of contract v1 (no contract change needed), or fold it into P7 navigation | open — provisional, awaiting user confirmation (not implemented) |
| OQ-P4-1 | Should review and goal writes (self-assessment, manager review, acknowledge, goals) and review generation be refused when the cycle is not OPEN (e.g. DRAFT or CLOSED)? Legacy never checked the cycle status | Raised in P4. Closing a cycle currently does not freeze its reviews | Refuse with 409 `INVALID_CYCLE_STATUS` (-20401) unless the cycle is OPEN (generation allowed in DRAFT and OPEN) | open — provisional, awaiting user confirmation (**not implemented**: P4 keeps parity because it is not blocked) |
| OQ-P4-2 | Should `PERFORMANCE:VIEW` (grade ≥ 5 under the D-06 matrix) read every review and its goals? The legacy form showed every review to every user | Raised in P4. Implemented as the minimal HR read scope of OQ-09 | Keep: employee + reviewer + `PERFORMANCE:VIEW`; alternatively restrict to `PERFORMANCE:ADMIN` or the management chain | open — provisional, awaiting user confirmation (implemented) |
| OQ-P4-3 | Should HR (`PERFORMANCE:ADMIN`) be able to act on a review on behalf of the employee or reviewer, or reassign the reviewer (e.g. after a manager change)? | Raised in P4. Legacy had no reassignment; OQ-09 says the reviewer or the employee | No override and no reassignment in P4; add a `PERFORMANCE:ADMIN` reassign command later if the user wants it | open — provisional, awaiting user confirmation (no override implemented) |

---

## 7. Amendment log

| Version / ID | Date | Phase | Change | Rationale |
|---|---|---|---|---|
| — | 2026-11-20 | P0 | Initial locked decisions D-00…D-09; contract `v0-draft` | Phase 0 deliverable. D-00 is a user decision relayed by the coordinator |
| A-P1-01 | 2026-11-20 | P1 | Added D-10 (backend Maven layout, Flyway conventions, dev seed, app-role grants, embedded-PG16 tests) | D-00 asked P1 to record the build layout. Additive; no locked decision changed |
| A-P1-02 | 2026-11-20 | P1 | **User decision:** the recommended defaults of OQ-01…OQ-04 and OQ-06…OQ-13 are accepted and closed; they are no longer provisional. Registry rows blocked on OQ-03, OQ-09, OQ-10, OQ-11 are unblocked; PKG-I01 is drop-proposed (OQ-13) | Relayed by the coordinator. Schema effects applied in P1: OQ-06 partial unique index (V6); OQ-13 tables migrated. OQ-07/OQ-08 need no P1 columns (P2 adds them with `user_credentials`) |
| A-P2-01 | 2026-11-20 | P2 | D-05: added the code `INVALID_CURRENT_PASSWORD` (422) for a wrong current password on change-password | Legacy `change_password` was a stub with no such check. 422 follows D-05's split (well-formed input that fails a rule); 401 would wrongly signal an invalid session. Additive; no existing code or status changed |
| A-P3-01 | 2026-11-20 | P3 | **Contract frozen at `v1`** (`openapi-v1.yaml` 1.0.0): P2 routes unchanged; added 11 leave operations (`/api/v1/leave/types`, `/balances`, `/requests` GET+POST, `/requests/{requestId}` + `/approve` `/reject` `/cancel`, `/approvals`, `/team-calendar`, `/accrual-runs`), leave schemas, and the complete `ErrorCode` enum for every phase (D-05 codes for P4–P7 included so they need no amendment) | P3 exit criterion. Later changes need `v1.N` (additive) or `v2` (breaking) |
| A-P3-02 | 2026-11-20 | P3 | D-05: `EMPLOYEE_NOT_ACTIVE` is always **422** (the -20012 transfer row said 409); `PARAM_NOT_EDITABLE` is always 409 and an unknown parameter is `NOT_FOUND` 404. Each `ErrorCode` now has exactly one HTTP status | One status per code keeps the frozen catalogue machine-checkable. Neither route exists yet (P5, P7), so no client is affected |
| A-P4-01 | 2026-11-20 | P4 | **Contract amendment `v1.1-P4`** (additive; `openapi-v1.yaml` 1.1.0): tag `performance`, 20 operations under `/api/v1/performance` (cycles list/create/get/delete-405/open/close, review generation, cycle reviews, rating distribution, my/team reviews, review get/delete-405, self-assessment, manager review, acknowledge, goals list/add, goal get/delete-405, goal progress); schemas `ReviewCycle`, `CreateReviewCycle`, `PerformanceReview`, `GenerateReviewsResult`, `SelfAssessment`, `ManagerReview`, `Acknowledgement`, `RatingBucket`, `PerformanceGoal`, `AddGoal`, `GoalProgress` and enums `CycleStatus`, `ReviewStatus`, `GoalStatus`, `GoalCategory`, `ReviewRole`; responses `PerformanceConflict`, `DeleteForbidden`; path parameters `CycleId`, `ReviewId`, `GoalId`. `OpenApiContractTest` accepts `v1(.N(-Pn))?` | P4 scope. No v1 route, schema, error code or status changed (the P4 codes were already in the v1 catalogue). Numbered `v1.1-P4` per the parallel-phase rule; the coordinator renumbers it when P4 and P5 merge |
| A-P4-02 | 2026-11-20 | P4 | D-10: P4 migrations use the coordinator-reserved range `V200–V299` (`V200__performance_workflow.sql`) instead of the next global number | Parallel-phase rule: P4 and P5 branch from the same P3 head; disjoint ranges avoid Flyway version clashes. Flyway orders V200 after V10; `ProductionMigrationTest` now expects 11 migrations |
