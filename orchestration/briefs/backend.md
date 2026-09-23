# Backend child — copy and fill all inputs before dispatch

**Repository:** `codev-workshops/ts-plsql-oracle-forms-hrms`. **Phase:**
`<P0–P5 / slug>`. **Pinned contract:** `<ref>@<SHA>` and `<contract child URL>`.
**Implementation branch and PR base:** `<refs>`. **Parent:** `<URL>`.
**Frozen OpenAPI/error-code/schema/authority summary:** `<paste contract report>`.
**Allowed scope and module/table ownership:** `<phase section and files>`.
**P3 dependency:** `<salary SHA / not applicable>`.

You are a standalone child with your own VM. Read `CUTOVER_PLAN.md` §§1–2
and the phase §4–9, `TEST_STRATEGY.md` §§2, 4–5, 7,
`COMPONENT_MAPPING.md` §§1–8, 11, `MODERNIZATION_BLUEPRINT.md`
§§8, 10, `ORCHESTRATION_PLAYBOOK.md` and the frozen contract. Do not run
the repo's dynamic-workflow skill. ARCH-03: auth owns session context.
ARCH-01: `SalaryService` owns `SALARY_RECORDS` before employee-service.
The target Spring application uses PostgreSQL, not an Oracle connection;
Oracle is the independent coexistence oracle.

Implement **or audit** Spring service/entities/Flyway migrations and
`hrms-validation` for this phase against the pinned OpenAPI and `ApiError.code`
values. Add or verify Level-1 JUnit/repository tests and phase scenarios in
`tools/parallel-run/`; preserve declared BUG divergences only. P0 includes
auth/SSO bridge, schema/seed, CDC and shared Spring modules; P3 salary is
a separate **preceding** backend child, employee work begins at its head;
P4 includes the payroll shadow engine. Do not alter the frozen contract,
proxy flags in production, or tests just to pass. A contract conflict is a
blocked report, not an implementation-side workaround.

Run focused lint/build/tests and report the commands. If there is a change,
open a PR into the given branch; otherwise pin the verified SHA (no empty
PR). Do not claim Level 2/3 or live Oracle evidence based on local fixtures.

**Return via `provide_structured_output` (all fields required):**
```json
{
  "phase": "<P0–P5>", "role": "backend",
  "status": "success|failed|blocked", "base_sha": "<40-hex>",
  "contract_sha": "<40-hex>", "head_ref": "<ref>",
  "head_sha": "<40-hex>", "pr_url": "<full URL or null>",
  "level1": "pass|fail|untested-live",
  "contract_conformant": true, "scenarios": ["<parallel-run ID>"],
  "artifacts": ["<result file or URL>"], "findings": ["<failure + owner>"],
  "evidence_gaps": ["<not run>"], "blockers": ["<action needed>"]
}
```
`success` requires no blockers and `level1=pass`; include actual commands
and results in the session report.
