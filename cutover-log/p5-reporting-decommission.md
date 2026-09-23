# Cutover log — Phase 5 (Reporting / Integration and decommission)

| Field | Value |
|---|---|
| Status | **PROMOTED** (local gate PASS; operational decommission not executed) |
| Date | 2026-09-23 (UTC) |
| Promoted revision | `a5d8e2b5e633f902c5e78fabccc903fe5b381c28` (`p5-reporting-decommission/integration`, round 3) |
| Phase branch | `phase/p5-reporting-decommission` fast-forwarded `e17a12c` → `a5d8e2b` |
| Base | `phase/p4-payroll` @ `e17a12c` (P4 promoted @ `2fd78ac` plus its cutover-log commit) |
| Validation mode | golden-oracle **OFF** — PostgreSQL 16 only; Oracle, Forms and utPLSQL not run; legacy expectations recorded from source |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/38#issuecomment-5790475507 |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §9.4)

| Gate | Result |
|---|---|
| Level 1 | Backend `mvn -B verify` (JDK 21) PASS; frontend Vitest 30 files / 193 tests PASS; typecheck and lint PASS. |
| Level 2 | PostgreSQL-backed `tools/parallel-run` REST runner PASS: 88/89 scenarios, one declared deferred `leave.batch.carryover.expire.bug-04` (unmounted endpoint). `legacy_source=recorded`, utPLSQL skipped. GL and benefits feed bytes matched committed golden fixtures. |
| Level 3 | PostgreSQL-only `tools/reconcile`: all six views RECONCILED against `tests/golden/views-baseline.csv` (0 mismatching cells). Phase-5 leave-summary pack RECONCILED against `tests/golden/views-baseline-p5-leave-summary.csv`; the three legacy/P5 `AVAILABLE` differences are the declared VAL-05 correction. |
| Playwright | PASS 4/4 on real React + Spring Boot + PostgreSQL stack with all module flags `NEW` and `decommission=NEW`. |
| Target architecture | PASS: no PL/SQL/PL/pgSQL, triggers, or Forms libraries in target code or PostgreSQL schema; backend runtime has no Oracle JDBC driver. |
| Oracle/Forms/CDC operations | **untested-live**: no Oracle runtime exists here. CDC shutdown, reverse extract, final extract, and legacy infrastructure retirement were delivered as code/unit tests only, not run live. |

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P5.zero-hit-30-days` | Pre-approved via `HRMS_WF_APPROVED_GATES`; no live proxy-log measurement performed by this promotion | project owner |

## PRs of this phase

| PR | Branch | Review state |
|---|---|---|
| [#35](https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/35) | `p5-reporting-decommission/contract` → `phase/p5-reporting-decommission` | ready for human review; commits contained in phase branch after fast-forward |
| [#37](https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/37) | `p5-reporting-decommission/backend` → `p5-reporting-decommission/contract` | ready for human review |
| [#36](https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/36) | `p5-reporting-decommission/frontend` → `p5-reporting-decommission/contract` | ready for human review |
| [#38](https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/38) | `p5-reporting-decommission/integration` → `phase/p5-reporting-decommission` | ready for human review; commits contained in phase branch after fast-forward |

The phase stack is merged bottom-up by humans into `phase/p4-payroll`; this promotion did not merge it there.

## Post-promotion actions unblocked for humans

No production proxy flags were changed and nothing on Oracle or WebLogic was stopped or dropped by this promotion. The following actions are **unblocked, not performed**:

1. Transfer reference-data ownership to the PostgreSQL admin UI, move reporting to `reporting=NEW`, and stop the one-way reference-data CDC after verifying its final drain and checksums (`DECOMMISSION_RUNBOOK.md`).
2. Check proxy access logs for the approved `P5.zero-hit-30-days` gate before retiring the legacy routes. Then decommission Forms and WebLogic (retaining the documented rollback window), remove the SSO bridge, archive the final Oracle extract including the six `VW_*` outputs, and decommission Oracle according to the runbook. None of these live actions was verified here.

The legacy `plsql/` and `forms/` directories remain read-only characterization references; this record does not modify or execute them.
