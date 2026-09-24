# Cutover log — Phase 3 (Employee)

> Historical record from an earlier dynamic-workflow run. Its "PROMOTED" label and
> preapproved calendar gate do not authorize or verify live cutover in the managed
> run. Oracle/Forms, live CDC and operational sign-off remain unverified.

| Field | Value |
|---|---|
| Status | **PROMOTED** (gate PASS) |
| Date | 2026-09-22 (UTC) |
| Promoted revision | `61ea0aca841ebee67ddcb0e94a5b1bb7e33eda74` (`p3-employee/integration`, round 3) |
| Phase branch | `phase/p3-employee` fast-forwarded `0f42335` → `61ea0ac` |
| Base | `phase/p2-leave` @ `0f42335` (P2 promoted @ `94933cd` + its cutover-log commit; see [p2-leave.md](p2-leave.md)) |
| Validation mode | golden-oracle **OFF** — local PostgreSQL only (docker `postgres:16`, Testcontainers); no Oracle / Forms / utPLSQL runtime (DECISION P0-D1) |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/28#issuecomment-5779511957 (round 3; rounds 1–2 FAIL → backend remediation `ecfada3`, `e769fbe`) |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §7.4)

| Level | Check | Result |
|---|---|---|
| 1 | backend `mvn -B verify` (Java 21; all modules incl. `salary`, `employee`, `tools/parallel-run`, `tools/reconcile`, `tools/cdc-sync`), Testcontainers PostgreSQL, Spotless | PASS — 241 tests, 0 failures/errors/skipped |
| 1 | frontend Vitest 26 files / 133 tests; `tsc -b --noEmit`; eslint (0 errors); `vite build` | PASS |
| 2 | `tools/parallel-run --only employee` REST runner vs PostgreSQL-backed backend (utPLSQL skipped, `legacy_source=recorded`) | PASS — 22/22 salary + employee scenarios, 0 TARGET-DIFF / unexplained diffs; `-20002` declared unreachable (BUG-01 race is not reproducible through the single-writer Java path) |
| 2 | Full registry on pristine seed (round 2, `e770900`) | 63/67 — 3 `leave.batch.*` DEFERRED to P5, 1 target-diff fixed in round-2 remediation (`sso.exchange.legacy-module` now targets `payroll`, still LEGACY in P3) |
| 3 | `tools/reconcile` on fresh seed vs `tests/golden/views-baseline.csv` | RECONCILED — 6/6 views, 0 mismatching cells (`VW_ACTIVE_EMPLOYEES` 23, `VW_ORG_HIERARCHY` 23, `VW_EMPLOYEE_COMPENSATION` 23, …) |
| 3 | Terminate emp 21 via `POST /api/employees/21/terminate` (2024-05-31), then reconcile vs `tests/golden/views-terminated-mid-manager.csv` | RECONCILED — 6/6 views, 0 mismatches; reports keep `ORG_PATH` through the terminated manager, `ORG_LEVEL`/`IS_LEAF` unchanged (Oracle `WHERE`-after-`CONNECT BY` semantics reproduced in the `WITH RECURSIVE` query) |
| E2E | Playwright stage 1 `employee=NEW_READONLY` on real stack (Vite → backend → PostgreSQL 16, Flyway V1–V5): staff search → detail → History/Salary tabs; no Save / Change salary / New employee controls, read-only banner; `POST /api/employees` → `409 MODULE_READ_ONLY` | PASS |
| E2E | Playwright stage 2 `employee=NEW`: create → `EMP-001000` + initial salary row via `SalaryService`; change salary → 2 rows, exactly one `current`, grade-band warning; duplicate e-mail (case-insensitive) → `-20502`; self/cyclic manager → `-20004`; terminate → status Terminated, 2nd terminate `422 -20005` with `traceId`; terminated employee's token → `401 TOKEN_INVALID`, re-login `401 -20301` | PASS |
| Phase | Stack order (`p3-employee/backend-salary` ancestor of `p3-employee/backend` and of `61ea0ac`); sole writer — no `salary_records` writer / `SalaryRecord` repository outside `backend/salary` (ARCH-01/ARCH-02) | PASS |
| Phase | Flyway V5 on PostgreSQL; `-20xxx` error codes via `ApiError.code` | PASS (Level 1/2) |
| Phase | utPLSQL `PKG_EMPLOYEE` characterization on Oracle; Forms `HRMS_EMPLOYEE` parity; `TRG_EMP_*` / `TRG_SALARY_AUDIT` replacement audit rows vs Oracle; BUG-01 concurrency test against Oracle | `untested-live` (no Oracle; expectations recorded in scenario registry / golden CSVs) |

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P3.readonly-then-write-bake` | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |

## PRs of this phase

| PR | Branch | State after promotion |
|---|---|---|
| #22 | `p3-employee/contract` → `phase/p3-employee` | contained in phase branch (GitHub shows it as merged because the fast-forward made the base contain its commits); ready for human review |
| #23 | `p3-employee/backend-salary` → `p3-employee/contract` | ready for human review |
| #27 | `p3-employee/backend` → `p3-employee/backend-salary` | ready for human review |
| #24 | `p3-employee/frontend` → `p3-employee/contract` | ready for human review |
| #28 | `p3-employee/integration` → `phase/p3-employee` | contained in phase branch (shown as merged for the same reason); ready for human review |

The stack is merged bottom-up by humans into `phase/p2-leave`; nothing was merged there by the promote step.

## Post-promotion state and unblocked actions

No production proxy flag was flipped and nothing was dropped on Oracle by this step. The following operational actions are now **unblocked** and are performed by humans:

1. Flip proxy flag `employee=NEW_READONLY` (Java reads, Forms `HRMS_EMPLOYEE` still writes; nightly Level-3 reconciliation of `VW_ACTIVE_EMPLOYEES`, `VW_EMPLOYEE_COMPENSATION`, `VW_ORG_HIERARCHY` must stay RECONCILED), then `employee=NEW` after the read-only bake (rollback: `employee=NEW_READONLY` → `LEGACY`, CUTOVER_PLAN.md §7; Oracle triggers are kept until phase exit).
2. Retire Forms `HRMS_EMPLOYEE` once traffic is on the new module.
3. Drop `TRG_EMP_*`, `TRG_SALARY_AUDIT` and `PKG_EMPLOYEE` on Oracle after approval (`P3.readonly-then-write-bake`, granted above) — i.e. after the write bake has run on the Java path with the three-view reconciliation still RECONCILED.

`phase/p3-employee` @ `61ea0ac` is the base branch for Phase 4 (Payroll).
