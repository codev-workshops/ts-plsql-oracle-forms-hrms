# P3 – Employee integration test, round 2

Tested: `p3-employee/integration` @ `e770900327384b004ca90bba07ee6c94440d71cc` (PR #28).
Golden-oracle mode OFF: PostgreSQL 17 (docker `hrms-pg`) only; legacy PL/SQL read as reference. No application code changed.

Verdict: **FAIL → failure_owner = backend** (one Level 2 TARGET-DIFF; every other gate passed).

| Gate | Result | Evidence |
|---|---|---|
| Level 1 backend (`mvn verify` on merged tree incl. tools, Java 21, Testcontainers PG) | PASS – 238 JUnit tests, 0 failures | `level1-junit-summary.md` |
| Level 1 frontend (Vitest) | PASS – 26 files / 133 tests | `level1-vitest.log` |
| Playwright real stack (Vite :5173 ↔ Spring :8080 ↔ PG), stage 1 `employee=NEW_READONLY`, stage 2 `employee=NEW`, one run, no msw | PASS | `playwright-html/index.html`, `stage1-api-evidence.json`, `stage2-api-evidence.json`, harness in `harness/` |
| Level 2 parallel-run, full registry (67 scenarios, `legacy_source=recorded`, pristine seed + `tools/parallel-run/fixtures/leave.sql`) | **FAIL – 63/67, 3 DEFERRED (P5 leave.batch), 1 TARGET-DIFF (F1)**; all 4 salary + 21 employee scenarios PASS; `-20002` declared unreachable (`contracts/p3-employee/error-codes.md` row `-20002`) | `parallel-run-clean.md` |
| Level 3 pristine seed vs `tests/golden/views-baseline.csv` (6 views incl. VW_ACTIVE_EMPLOYEES = directory, VW_ORG_HIERARCHY ORG_LEVEL/ORG_PATH/IS_LEAF, VW_EMPLOYEE_COMPENSATION) | PASS – 0 mismatching cells | `reconcile-baseline.md` |
| Level 3 scripted write `tests/golden/scenarios/terminated-mid-manager.sql` vs `views-terminated-mid-manager.csv` | PASS – 0 mismatching cells | `reconcile-terminated-mid-manager.md` |
| Level 3 same write performed through the Java path (`POST /api/employees/21/terminate` effectiveDate 2024-05-31, EmployeeService → SalaryService.closeActive) vs `views-terminated-mid-manager.csv` | PASS – 0 mismatching cells | `reconcile-terminated-mid-manager-via-api.md`, `terminate-21-api-response.json` |
| Phase-specific stack order | PASS – `p3-employee/backend-salary` (06eb55c) is an ancestor of `p3-employee/backend` (ecfada3) and of e770900; `backend/salary` differs from the salary branch only by a 3-line test cleanup; no `insert/update/delete … salary_records` outside `backend/salary/src/main`; no `JpaRepository`/`@Entity`/`SalaryRecordRepository` reference outside `backend/salary`; `EmployeeService` writes salary only via `SalaryService.createInitial/closeActive`; no PL/pgSQL/trigger in Flyway migrations | this file |

## Playwright stage detail (all steps passed, see `stage*-api-evidence.json`)

Stage 1 (`NEW_READONLY`): login, search, open detail, history + salary sections, write attempts rejected with `MODULE_READ_ONLY` and no write controls exposed.
Stage 2 (`NEW`): create → `201` with server `empNumber` from `seq_emp_number` and exactly one active `salary_records` row created via SalaryService; salary change → prior row `end_date` set/`active=false`, new row active; duplicate active e-mail → `409 -20502` (`field=email`, rendered inline); self-manager cycle → `400 -20004`; terminate → `200`, second terminate → `422 -20005`; terminated user's token → `TOKEN_INVALID` (session revoked).

## Findings (routed)

F1 `[backend]` `tools/parallel-run` `ScenarioRegistry.sso.exchange.legacy-module` requests `module=employee`. Since P3 promotes the employee module (`HRMS_FLAG_EMPLOYEE=NEW`, which the runner README itself requires), the auth-service correctly answers `SSO_MODULE_NOT_LEGACY`, but the golden-oracle-OFF target expectation is still `SSO_LEGACY_UNAVAILABLE` → TARGET-DIFF, runner exit 1. Fix the recorded fixture: exchange a module that is still legacy in P3 (e.g. `payroll`, verified manually to return `SSO_LEGACY_UNAVAILABLE`) or make the expected outcome flag-aware. Application code is behaving per contract; this is a stale Level 2 fixture, not an auth-service defect.

Informational (not counted against the gate):
- `seq_emp_number` on a dev seed starts at 1000 (`V1__hrms_schema.sql`), so the first created employee is `EMP-001000` while the seed max is `EMP-000099` (`stage2-sequence-mismatch.json`). The `MAX+1` restart is a cutover-time step owned by `tools/cdc-sync` (`EmployeeCutoverTest`, `untested-live`), not the dev fixture; numbers are unique and sequence-derived, so `-20002` stays unreachable.
- The assignment names `VW_EMPLOYEE_DIRECTORY`; the legacy schema (`schema/views/hrms_views.sql`) has no such view — `VW_ACTIVE_EMPLOYEES` is the directory view and was reconciled.
- CDC / reverse-extract / decommission: covered by unit tests in Level 1 only, `untested-live`.

## Method / environment notes

- Default JDK on the box is 11; Maven needs `PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH` with `JAVA_HOME` unset.
- Docker Hub rate-limited `postgres:16-alpine`; the local `postgres:17` image was tagged as `postgres:16-alpine` for Testcontainers (harness only).
- Order that works: start PG → start backend (Flyway V1–V6) → load `tools/fixtures/pg/01…04` → (Level 2 only) `tools/parallel-run/fixtures/leave.sql` → run. Level 2 and Level 3 were each executed on a freshly recreated database; runs on a database already mutated by e2e/scenarios produce spurious diffs and were discarded.
- Backend flags for Level 2: `HRMS_FLAG_EMPLOYEE=NEW HRMS_FLAG_LEAVE=NEW HRMS_FLAG_PERFORMANCE=NEW HRMS_PROXY_CIDRS=127.0.0.1/32,::1/128`.
- The committed `frontend/e2e/employee-golden-path.spec.ts` is msw-only (skips under `E2E_REAL_STACK=1`); the real-stack harness used is in `harness/` (`playwright.real.config.ts`, two stage specs), not added to the app tree.
