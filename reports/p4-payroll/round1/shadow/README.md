# PayrollShadowRunner – per-run totals (Level 2 gate artifact)

Source: `GET /api/payroll/shadow/runs/{runId}/diff` (ADMIN:VIEW), persisted in `payroll_shadow_reports`; legacy side = `tests/golden/payroll/202406.json` (`legacySource=recorded`, PKG_PAYROLL.calculate_payroll evaluated over the seed).

| run | period | engine | legacy source | employees | matched | explained | unexplained | legacyOnly | javaOnly | err legacy | err java | net delta (cents) | file |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 9004 | 202406 | JAVA | recorded | 23 | 92 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | `shadow-diff-api-run-9004-calculated.json` |
| 9006 | 200001 | JAVA | recorded | 23 | 0 | 0 | 0 | 0 | 0 | 0 | 23 | 0 | `shadow-diff-api-run-9006-no-active-salary.json` |
| 9007 | 202406 | JAVA | recorded | 23 | 92 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | `shadow-diff-api-run-9007-approved.json` |
| 9003 | 202406 | JAVA | recorded | 23 | 92 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | `shadow-diff-ui-run-9003-approved.json` |

Every `(RUN, EMP_ID, ELEMENT_ID)` of the 2024-rule seed diffs to 0 cents (92/92 MATCH, 23 employees) for runs calculated via REST (9004/9007) and via the UI (9003). Run 9006 (period 200001, no active salary) yields the expected 23 `-20104` ERROR sentinel rows and no amounts.
