# Cutover log — Phase 1 (Performance)

| Field | Value |
|---|---|
| Status | **PROMOTED** (gate PASS) |
| Date | 2026-09-22 (UTC) |
| Promoted revision | `3bae336d587884f15dee4689538f3a1b99759dae` (`p1-performance/integration`, round 2) |
| Phase branch | `phase/p1-performance` fast-forwarded `d35bdbc` → `3bae336` |
| Base | `phase/p0-foundation` @ `ea5925e` (see [p0-foundation.md](p0-foundation.md)) |
| Validation mode | golden-oracle **OFF** — local PostgreSQL only (docker `postgres:17`, Testcontainers); no Oracle / Forms / utPLSQL runtime (DECISION P0-D1) |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/17#issuecomment-5760011531 (round 1: https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/17#issuecomment-5759583076) |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §5.5)

| Level | Check | Result |
|---|---|---|
| 1 | backend `mvn -B verify` (all modules incl. `performance-service`, `tools/*`), Testcontainers PostgreSQL | PASS |
| 1 | frontend Vitest 18 files / 78 tests; `tsc -b`; eslint | PASS — 0 errors / 5 warnings |
| 2 | `tools/parallel-run` REST runner vs PostgreSQL-backed backend (`legacy_source=recorded`) | PASS — 19/20; all 10 `performance.*` scenarios PASS, 0 diffs; `sso.exchange.legacy-module` = `UNTESTED-LIVE` (P0-D1) |
| 3 | `tools/reconcile compare` on fresh seed vs `tests/golden/views-baseline.csv` | RECONCILED — 6/6 views, 0 mismatching cells |
| 3 | `tools/reconcile` after scripted actions (Playwright + parallel-run on the same DB) | PASS — 5 views 0 mismatching cells; `VW_PENDING_APPROVALS` PERFORMANCE rows == `performance_reviews.status='MANAGER_REVIEW'` grouped by approver; LEAVE rows untouched; diff vs baseline == exactly the 2 `Parallel-run cycle` reviews created by the Level-2 script |
| E2E | Playwright golden path on real stack (Vite → backend `HRMS_FLAG_PERFORMANCE=NEW` → PostgreSQL): admin creates/opens cycle + generates reviews → employee self-assessment → manager review → goal 100% COMPLETED → employee acknowledges | PASS (`e2e/integration-p1-golden-path.spec.ts`) |
| Phase | Flyway V3 on PostgreSQL; `-20xxx` error codes via `ApiError.code` | PASS (Level 1/2) |
| Phase | utPLSQL `PKG_PERFORMANCE` characterization on Oracle; Forms `HRMS_PERFORMANCE` parity | `untested-live` (no Oracle; expectations recorded in scenario registry) |

Non-gating findings from round 2 (P0 `e2e/golden-path.spec.ts` password-change toast race) are tracked in the integration report.

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P1.bake-4-weeks` | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |

## PRs of this phase

| PR | Branch | State after promotion |
|---|---|---|
| #14 | `p1-performance/contract` → `phase/p1-performance` | contained in phase branch; ready for human review |
| #16 | `p1-performance/backend` → `p1-performance/contract` | ready for human review |
| #15 | `p1-performance/frontend` → `p1-performance/contract` | ready for human review |
| #17 | `p1-performance/integration` → `phase/p1-performance` | contained in phase branch (GitHub shows it as merged because the fast-forward made the base contain its commits); ready for human review |

The stack is merged bottom-up by humans into `phase/p0-foundation`; nothing was merged there by the promote step.

## Post-promotion state and unblocked actions

No production proxy flag was flipped and nothing was dropped on Oracle by this step. The following operational actions are now **unblocked** and are performed by humans:

1. Flip proxy flag `performance=NEW` (rollback: `performance=LEGACY`, CUTOVER_PLAN.md §5.4).
2. Retire Forms `HRMS_PERFORMANCE` once traffic is on the new module.
3. Drop `PKG_PERFORMANCE` on Oracle after the 4-week bake approval (`P1.bake-4-weeks`, granted above).

`phase/p1-performance` @ `3bae336` is the base branch for Phase 2 (Leave).
