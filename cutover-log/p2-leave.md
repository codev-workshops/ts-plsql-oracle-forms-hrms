# Cutover log — Phase 2 (Leave)

| Field | Value |
|---|---|
| Status | **PROMOTED** (gate PASS) |
| Date | 2026-09-22 (UTC) |
| Promoted revision | `94933cdbe78201bab64b3470cd23b1bf666e8cd2` (`p2-leave/integration`, round 1) |
| Phase branch | `phase/p2-leave` fast-forwarded `9a15d26` → `94933cd` |
| Base | `phase/p1-performance` @ `9a15d26` (see [p1-performance.md](p1-performance.md)) |
| Validation mode | golden-oracle **OFF** — local PostgreSQL only (docker `postgres:16`, Testcontainers); no Oracle / Forms / utPLSQL runtime (DECISION P0-D1) |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/21#issuecomment-5774787786 |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §6.4)

| Level | Check | Result |
|---|---|---|
| 1 | backend `mvn -B verify` (all modules incl. `leave`, `tools/parallel-run`, `tools/reconcile`, `tools/cdc-sync`), Testcontainers PostgreSQL | PASS — 176 tests, 0 failures |
| 1 | frontend Vitest 23 files / 104 tests; `tsc -b`; eslint | PASS — 0 errors / 5 warnings |
| 2 | `tools/parallel-run` REST runner vs PostgreSQL-backed backend (utPLSQL skipped, `legacy_source=recorded`) | PASS — 41/45; all `leave.*` REST scenarios PASS incl. error codes `-20201…-20212`; `sso.exchange.legacy-module` = `UNTESTED-LIVE` (P0-D1); 3 `leave.batch.*` scenarios DEFERRED (batch endpoints mounted in P5; BUG-04 expire scenario is among them) |
| 2 | Documented behavioural divergences (TECH_DEBT_REGISTRY) | BUG-05 (Saturday holiday counted) and BUG-06 (AM+PM half-days same day) fixed in the target and recorded as expected divergences in the scenario registry |
| 3 | `tools/reconcile compare --as-of 2024-06-30` on fresh seed vs `tests/golden/` | RECONCILED — 6/6 views, 0 mismatching cells (`VW_LEAVE_SUMMARY` 11/11, `VW_PENDING_APPROVALS` 5/5) |
| 3 | VAL-05 documented `PENDING` offset | verified — `view.AVAILABLE − table.available == PENDING` on all 12 seed balance rows |
| E2E | Playwright golden path on real stack (Vite `VITE_MODULE_FLAGS=leave=NEW` → backend → PostgreSQL 16, Flyway V1–V4): employee submits Mon–Wed request (3 business days, balance 10 → pending 3 / available 7) → manager approves → employee sees APPROVED with used 3 / pending 0 | PASS — 2/2 |
| Phase | Flyway V4 on PostgreSQL; `-20xxx` error codes via `ApiError.code` | PASS (Level 1/2) |
| Phase | utPLSQL `PKG_LEAVE` characterization on Oracle; Forms `HRMS_LEAVE` parity; `TRG_LEAVE_REQUEST_AUDIT` replacement audit rows vs Oracle | `untested-live` (no Oracle; expectations recorded in scenario registry / golden CSVs) |

Non-gating findings (the committed `frontend/e2e/leave-golden-path.spec.ts` self-skips under `E2E_REAL_STACK=1`; the real-stack spec used by the integration session is attached to the report but not committed) are tracked in the integration report.

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P2.accrual-cycle` | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |

## PRs of this phase

| PR | Branch | State after promotion |
|---|---|---|
| #18 | `p2-leave/contract` → `phase/p2-leave` | contained in phase branch (GitHub shows it as merged because the fast-forward made the base contain its commits); ready for human review |
| #20 | `p2-leave/backend` → `p2-leave/contract` | ready for human review |
| #19 | `p2-leave/frontend` → `p2-leave/contract` | ready for human review |
| #21 | `p2-leave/integration` → `phase/p2-leave` | contained in phase branch (shown as merged for the same reason); ready for human review |

The stack is merged bottom-up by humans into `phase/p1-performance`; nothing was merged there by the promote step.

## Post-promotion state and unblocked actions

No production proxy flag was flipped and nothing was dropped on Oracle by this step. The following operational actions are now **unblocked** and are performed by humans:

1. Flip proxy flag `leave=NEW` (rollback: `leave=LEGACY` + accrual/carry-over job swap back to the Oracle scheduler, CUTOVER_PLAN.md §6.3; disable the Spring job and re-enable any legacy job before rolling back).
2. Retire Forms `HRMS_LEAVE` once traffic is on the new module.
3. Drop `PKG_LEAVE` and `TRG_LEAVE_REQUEST_AUDIT` on Oracle after the accrual-cycle approval (`P2.accrual-cycle`, granted above) — i.e. after one accrual cycle has run on the Java job with the `VW_LEAVE_SUMMARY` reconciliation still RECONCILED.

`phase/p2-leave` @ `94933cd` is the base branch for Phase 3 (Employee).
