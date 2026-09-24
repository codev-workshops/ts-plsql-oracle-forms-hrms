# Cutover log — Phase 0 (Foundation)

| Field | Value |
|---|---|
| Status | **PROMOTED** (gate PASS) |
| Date | 2026-09-21 (UTC) |
| Promoted revision | `ea5925e4d8030755e53985d7d7e5b9de511fadce` (`p0-foundation/integration`, round 3) |
| Phase branch | `phase/p0-foundation` fast-forwarded `ad92ddf` → `ea5925e` |
| Validation mode | golden-oracle **OFF** — local PostgreSQL 16 only; no Oracle / Forms / utPLSQL runtime (DECISION P0-D1) |
| Integration report | https://github.com/codev-workshops/ts-plsql-oracle-forms-hrms/pull/11#issuecomment-5758603003 (round 1/2 artefacts: PR #12, PR #13 → `reports/p0-integration/`) |

## Gate results (TEST_STRATEGY.md §5, CUTOVER_PLAN.md §4.4)

| Level | Check | Result |
|---|---|---|
| 1 | backend `mvn verify` (10 modules incl. `tools/*`), Spotless | PASS — 70 tests, 0 failures |
| 1 | frontend Vitest / `tsc -b` / eslint | PASS — 64 tests, 0 lint errors |
| 1 | `proxy/test_proxy_config.py` | PASS — 5 |
| 2 | `tools/parallel-run` vs PostgreSQL-backed auth-service (`legacy_source=recorded`) | PASS — 9/10; `sso.exchange.legacy-module` = `UNTESTED-LIVE` (P0-D1); documented divergence `auth.lockout.after-5-failures` (SEC-05: legacy `-20301` vs target `RATE_LIMITED` 429) |
| 3 | `tools/reconcile compare` vs `tests/golden/views-baseline.csv` (`--as-of 2024-06-30`) | PASS — 6/6 views, 0 mismatching cells |
| 3 | Seed-count parity, 30 legacy tables (recorded Oracle fixture INSERT counts vs PostgreSQL `count(*)`) | PASS — 30/30 |
| E2E | Playwright golden path on real stack (Vite → auth-service → PostgreSQL) | PASS — 5 passed, 1 skipped (legacy tile, P0-D1) |
| Phase | Flyway V1+V2 on empty PostgreSQL; 401/refresh/logout flow | PASS |
| Phase | utPLSQL golden suites on Oracle; SSO bridge → Forms session; CDC Oracle leg | `untested-live` (no Oracle; PostgreSQL-only `HolidaysRoundTripTest` PASS) |

## Approvals

| Gate | Decision | By |
|---|---|---|
| `P0.security-signoff` (SEC-01/02/05/07/08 auth divergences) | Approved — pre-approved via `HRMS_WF_APPROVED_GATES` | project owner |

## PRs of this phase

| PR | Branch | State after promotion |
|---|---|---|
| #8 | `p0-foundation/contract` → `phase/p0-foundation` | contained in phase branch; ready for human review |
| #10 | `p0-foundation/backend` → `p0-foundation/contract` | ready for human review |
| #9 | `p0-foundation/frontend` → `p0-foundation/contract` | ready for human review |
| #11 | `p0-foundation/integration` → `phase/p0-foundation` | contained in phase branch; ready for human review |

The stack is merged bottom-up by humans into `devin/1789629102-hrms-analysis-artifacts`; nothing was merged there by the promote step.

## Post-promotion state and unblocked actions

- **Nothing goes live for end users.** The proxy still routes every module (including `auth`) to Oracle Forms; no production proxy flag was flipped and nothing was dropped on Oracle. Those are operational steps performed by humans after this record.
- `phase/p0-foundation` @ `ea5925e` is the base branch for Phase 1 (Performance).
- Items delivered as code + unit tests only and to be exercised when an Oracle environment exists: SSO bridge end-to-end (`POST /legacy/sso/exchange` → Forms session), CDC `tools/cdc-sync` Oracle leg, utPLSQL characterization suites under `tests/`.
