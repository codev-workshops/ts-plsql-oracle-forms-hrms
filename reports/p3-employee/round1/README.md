# P3 – Employee integration test, round 1

Tested: `p3-employee/integration` @ `c2e7d1018fa64f068ef70133af4c0ca5f51be2fa` (PR #28).
Golden-oracle mode OFF: PostgreSQL 16 (docker `hrms-pg`) only; legacy PL/SQL read as reference.

| Gate | Result | Evidence |
|---|---|---|
| Level 1 backend (`mvn verify`, 234 JUnit tests) | PASS | `level1-backend-summary.log` |
| Level 1 frontend (Vitest, 133 tests / 26 files) | PASS | `level1-vitest.log` |
| Playwright real stack (stage 1 `employee=NEW_READONLY` @8080/5173, stage 2 `employee=NEW` @8082/5174) | PASS (after harness-side `setval('seq_salary')`, see F1) | `playwright-html/index.html`, harness `frontend/e2e/p3-real-stack.spec.ts` + `frontend/playwright.p3.config.ts` |
| Level 2 parallel-run salary+employee (21 scenarios, `legacy_source=recorded`) | FAIL – 1 TARGET-DIFF (F2) | `parallel-run.md` |
| Level 3 pristine seed vs `tests/golden/views-baseline.csv` (6 views) | PASS (0 mismatching cells) | `reconcile-baseline-pristine.md` |
| Level 3 after scripted writes (key-matched by emp_id + semantic invariants + Oracle-semantics cross-check) | FAIL (F3, F4) | `reconcile-post-writes-keyed.md`, `vw_org_hierarchy-oracle-vs-pg-semantics.txt`, raw tool output `reconcile-post-writes.md` |
| Phase-specific: `p3-employee/backend-salary` (06eb55c) is an ancestor of `p3-employee/backend` (1a9dc66) which is an ancestor of c2e7d10; salary module untouched by the employee commit; `SalaryRecordRepository` is the only class touching `salary_records` and lives in `backend/salary`; no `Repository<SalaryRecord…>`/JPA repository anywhere; `-20002` declared unreachable (error-codes.md row `-20002`) | PASS | this file |

Findings (routed): `findings.md`.

Notes on method: `tools/reconcile compare` is a positional row_no comparison against the pristine seed, so after Level 2 / e2e writes
its raw output (`reconcile-post-writes.md`) is dominated by row shifts. `l3_keyed_check.py` re-runs the same
`tests/reconciliation/pg/*.sql` queries and compares per `emp_id`, explains cells touched by the scripted writes
(emp 1 salary scenarios, emp 12/21 terminations, new emp_ids ≥ 10001), checks invariants, and `org_oracle_semantics.sql`
evaluates the legacy `CONNECT BY … WHERE` semantics on the same PostgreSQL data to expose the VW_ORG_HIERARCHY divergence.
Harness-only DB mutations: `setval('seq_salary')` (F1) and re-activating emp 21 / its login between Playwright re-runs.
