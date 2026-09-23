# Frontend child — copy and fill all inputs before dispatch

**Repository:** `codev-workshops/ts-plsql-oracle-forms-hrms`. **Phase:**
`<P0–P5 / slug>`. **Pinned contract:** `<ref>@<SHA>` and `<contract child URL>`.
**Implementation branch and PR base:** `<refs>`. **Parent:** `<URL>`.
**Frozen endpoints/DTO/error-code/authority/schema summary:** `<paste report>`.
**Allowed module/pages:** `<phase section and files>`.

You are a standalone child with your own VM. Read `CUTOVER_PLAN.md` §§1–2
and your phase's §4–9, `TEST_STRATEGY.md` §§2, 4–5, 7,
`COMPONENT_MAPPING.md` §§1–8, 11, `MODERNIZATION_BLUEPRINT.md`
§§8, 10, `ORCHESTRATION_PLAYBOOK.md` and the pinned contract.
Do not run the dynamic-workflow skill. Auth owns session context (ARCH-03);
P3 salary-module must be ready before employee write integration (ARCH-01).

Implement **or audit** React module pages, routing and states against the
frozen OpenAPI/authorities/`ApiError.code`, reusing `AuthContext`,
`ProtectedRoute`, `Toolbar`, `useErrorHandler` and `ReferenceDropdown`
where applicable. Consume `frontend/src/generated/validation-schema.json`
exported by `hrms-validation`; do not hand-edit it or the contract.
Add/verify Vitest component tests and Playwright golden-path coverage
(tests are authored here, but the integration child executes real-stack
E2E). P0 builds the app shell; P4 payroll pages remain hidden until
**both** `payroll.engine=JAVA` and `payroll=NEW` receive human approval.
Never simulate a production cutover or change tests to make them pass.

Run scoped lint, typecheck and Vitest; report commands. If changed, open
a PR to the specified branch; otherwise report a pinned SHA, not an empty
PR. A mismatch with the frozen contract blocks and returns to the parent.

**Return via `provide_structured_output` (all fields required):**
```json
{
  "phase": "<P0–P5>", "role": "frontend",
  "status": "success|failed|blocked", "base_sha": "<40-hex>",
  "contract_sha": "<40-hex>", "head_ref": "<ref>",
  "head_sha": "<40-hex>", "pr_url": "<full URL or null>",
  "level1": "pass|fail|untested-live",
  "contract_conformant": true, "pages": ["<route>"],
  "e2e_cases": ["<scenario>"], "artifacts": ["<result file or URL>"],
  "findings": ["<failure + owner>"], "evidence_gaps": ["<not run>"],
  "blockers": ["<action needed>"]
}
```
`success` requires no blockers and `level1=pass`. Do not report
Playwright coverage as a passing **real-stack** E2E run unless executed.
