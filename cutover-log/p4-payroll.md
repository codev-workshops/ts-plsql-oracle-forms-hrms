# Cutover log — Phase 4 (Payroll)

> Historical record from an earlier dynamic-workflow run. Its "PROMOTED" label and
> preapproved calendar gate do not authorize or verify live cutover in the managed
> run. Oracle/Forms, live CDC and operational sign-off remain unverified.

| Field | Value |
|---|---|
| Status | **PROMOTED** (gate PASS) |
| Date | 2026-09-23 (UTC) |
| Promoted revision | `2fd78acd495b5adebaa7117ae16ef46c9666774a` (`p4-payroll/integration`, round 2) |
| Phase branch | `phase/p4-payroll` fast-forwarded `36cb03f` → `2fd78ac` |
| Base | `phase/p3-employee` @ `36cb03f` (P3 promoted @ `61ea0ac` + its cutover-log commit; see [p3-employee.md](p3-employee.md)) |
| Validation mode | golden-oracle **OFF** — local PostgreSQL 16 only (Testcontainers / docker); no Oracle / Forms / utPLSQL runtime (DECISION P0-D1) |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/33#issuecomment-5782554516 (round 2; round 1 FAIL @ `36de042`, owner=backend → remediation `bb4485b`; evidence pack PR #34) |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §8.4)

Flags during the gate: `auth/employee/leave/performance/payroll=NEW`, `payroll.engine=JAVA`.

| Level | Check | Result |
|---|---|---|
| 1 | backend `mvn -B verify` (Java 21; all modules incl. `payroll`, `tools/parallel-run`, `tools/reconcile`, `tools/cdc-sync`), Testcontainers PostgreSQL, Spotless | PASS — 0 failures |
| 1 | frontend Vitest 27 files / 155 tests; `tsc -b --noEmit`; eslint (0 errors); `vite build` | PASS |
| 2 | `tools/parallel-run` full registry vs PostgreSQL-backed backend (utPLSQL skipped, `legacy_source=recorded`) | PASS — 69/73 PASS, 1 `untested-live` (SSO legacy-module), 3 `leave.batch.*` DEFERRED to P5; all `payroll.*` scenarios PASS, order-independent after round-1 remediation |
| 2 | `PayrollShadowRunner` shadow diff on seed period (`payroll.shadow.seed-period`) | PASS — 97 matched / 0 unexplained / `netDeltaCents=0` |
| 3 | `tools/reconcile` on pristine seed vs `tests/golden/views-baseline.csv` | RECONCILED — 6/6 views, 0 mismatching cells |
| 3 | After Java run 202406 create → calculate → approve: `VW_PAYROLL_LATEST` vs `tests/golden/payroll/vw_payroll_latest-202406-approved.csv` (23 rows); `VW_EMPLOYEE_COMPENSATION` unchanged | RECONCILED — 0 mismatching cells |
| E2E | Playwright payroll golden path on the real stack (Vite → backend → PostgreSQL 16): HR opens JUN-2024 → Create Run → async Calculate → Pay Details (FED/STATE/FICA/MEDICARE negative, BASE_PAY positive) → Approve → payslip YTD 52 500.00 → `register.csv` with `BANK_NAME,ROUTING_LAST4,ACCOUNT_LAST4` masked `****NNNN` | PASS (2/2) |
| Phase | Shadow report artifact with per-run totals (run 9003: 23 employees, 97 matched, 0 unexplained, `netDeltaCents` 0) persisted in `payroll_shadow_reports` | PASS |
| Phase | `PayrollShadowRunner` schedulable against production runs: `GET /api/payroll/shadow/runs/{runId}/diff` mounted regardless of the `payroll` flag, `ADMIN:VIEW`-gated, idempotent/recomputable | PASS (no in-process scheduler; cron/CI polls per run) |
| Phase | Pure Java `TaxEngine` / `PayrollRunService` (no PL/SQL, PL/pgSQL, triggers in target); `-20xxx` codes via `ApiError.code` (`-20104` on the 200001 scenario) | PASS (Level 1/2) |
| Phase | utPLSQL `PKG_PAYROLL` characterization on Oracle; Forms `HRMS_PAYROLL` parity; shadow mode against ≥ 3 real production periods on the live `PKG_PAYROLL` result; `oracle-cdc` shadow branch; reverse extract to Oracle | `untested-live` (no Oracle; expectations recorded in scenario registry / golden CSVs; CDC/reverse-extract delivered as code + unit tests) |

Non-gating findings carried forward (from the round-2 report): P0 `e2e/golden-path.spec.ts` assumptions under `employee=NEW` and a racy password-change assertion (test authoring); `PAYROLL_RUNS.*_BY` written as JWT `sub` per the P0–P3 `caller.userId()` convention rather than the contract's `jwt.username` (flagged to the contract owner); real-stack runs need `HRMS_FIELD_KEY_BASE64` exported or `ACCOUNT_LAST4` renders `****`.

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P4.shadow-gate` | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |
| `P4.rollback-window-closed` | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |

## PRs of this phase

| PR | Branch | State after promotion |
|---|---|---|
| #30 | `p4-payroll/contract` → `phase/p4-payroll` | contained in phase branch (GitHub shows it as merged because the fast-forward made the base contain its commits); ready for human review |
| #32 | `p4-payroll/backend` → `p4-payroll/contract` | ready for human review |
| #31 | `p4-payroll/frontend` → `p4-payroll/contract` | ready for human review |
| #33 | `p4-payroll/integration` → `phase/p4-payroll` | contained in phase branch (shown as merged for the same reason); ready for human review |
| #34 | `devin/1790100814-p4-it-round1-reports` → `main` | round-1 evidence pack (informational) |

The stack is merged bottom-up by humans into `phase/p3-employee`; nothing was merged there by the promote step.

## Post-promotion state and unblocked actions

No production proxy flag was flipped and nothing was dropped on Oracle by this step. The following operational actions are now **unblocked** and are performed by humans:

1. Promote `payroll.engine=JAVA` **and** `payroll=NEW` together (§2 rule 6 cutover migration) — only after `P4.shadow-gate` (granted above). Rollback: `payroll.engine=LEGACY` + `payroll=LEGACY`, then the reverse extract of `PAY_PERIODS`/`PAYROLL_RUNS`/`PAYROLL_DETAILS` back to Oracle (CUTOVER_PLAN.md §8; rollback window three production periods).
2. Retire Forms `HRMS_PAYROLL` once traffic is on the new module.
3. Drop `PKG_PAYROLL` on Oracle after `P4.rollback-window-closed` (granted above) — i.e. after three production periods on PostgreSQL with `VW_PAYROLL_LATEST` still reconciled to the cent.

`phase/p4-payroll` @ `2fd78ac` is the base branch for Phase 5 (Reporting/Integration and decommission).
