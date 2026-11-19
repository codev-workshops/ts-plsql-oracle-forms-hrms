# Modernization Checkpoint

Read this first in a fresh session. Full detail: [`inventory.md`](inventory.md).

## Current state

| | |
|---|---|
| Phase | **1 — Discover: complete, awaiting user approval of the exit gate** |
| Exit gate | Inventory PR (this branch) + Mermaid dependency diagrams (`inventory.md` §6) |
| Repo / base | `codev-workshops/ts-plsql-oracle-forms-hrms`, `main` @ `de012585455275f35304d53327b6364fdba41712` |
| Head | Tip of the Phase 1 PR branch `devin/1795070209-phase1-inventory` (docs-only; SHA recorded in the PR) |
| Contract version | None yet. OpenAPI `v0.1.0` is a Phase 2 deliverable |
| Rule | Do **not** start a phase until the user approves the previous phase's exit gate. Ignore all Oracle Forms tests |

## Decisions made in Phase 1

1. Modules: Platform/Common, Security, Notification, Audit, Employee (+Org), Leave, Payroll (+Integration), Performance, Reporting (`inventory.md` §2).
2. The README's `PKG_EMPLOYEE` ↔ `PKG_PAYROLL` cycle is not present in code; the real cycle is `PKG_SECURITY` → `PKG_EMPLOYEE.set_session_context` (removed by Spring Security).
3. Independent (parallel-safe) Phase 3 slices: **Employee, Performance, Audit**. Not independent: **Payroll** (after Employee, shares salary writes via `SalaryPort`), **Reporting** (last).
4. Slice order: Phase 2 platform baseline + Leave pilot → Wave A {Employee, Performance, Audit} → Wave B Payroll → Wave C Reporting.

## Open decisions for the user (proposed defaults)

1. Bug parity vs. fix [fix, with tests documenting legacy behaviour].
2. Future hire-date limit [90 days].
3. Salary outside grade [warn + override reason].
4. Security/Common/Notification in Phase 2 platform baseline [yes].
5. `PKG_INTEGRATION` [part of Payroll slice; time import remains a stub].
6. Hard-coded 2024 tax tables [move to `TAX_BRACKETS` seed].

## Evidence

- `docs/modernization/inventory.md` — built from static reading of all 11 packages (spec+body), 6 triggers, 30 tables, 6 views, 29 sequences, 6 form XML exports, 2 PLL libraries, 1 menu, 2 seed scripts.
- No code was executed; items marked **(verify)** in the inventory need a test in Phase 2/3.

## Blockers

- None. Repo has no build, tests, CI or pre-commit hooks; Phase 2 must add the Java/React/Postgres toolchain.

## Next task (only after approval)

Phase 2 — Define: Postgres schema + Flyway migrations for all 30 tables and seed; OpenAPI contract for all modules; Leave pilot end to end (Spring Boot API + React screen + tests); write `docs/modernization/slice-recipe.md`; update this checkpoint; stop for approval.
