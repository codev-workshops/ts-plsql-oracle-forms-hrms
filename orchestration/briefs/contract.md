# Contract child — copy and fill all inputs before dispatch

**Repository:** `codev-workshops/ts-plsql-oracle-forms-hrms`. **Phase:** `<P0–P5 / slug>`.
**Pinned base:** `<branch>@<SHA>`. **Contract branch/PR base:** `<refs>`.
**Parent session:** `<URL>`. **Prior gate approval:** `<evidence URL, or none>`.

You are a standalone child with your own VM. Read `CUTOVER_PLAN.md` §§1–2
and your phase's §4–9, `TEST_STRATEGY.md` §§2, 4–5, 7,
`COMPONENT_MAPPING.md` §§1–8, 11, `MODERNIZATION_BLUEPRINT.md`
§§8, 10, `ORCHESTRATION_PLAYBOOK.md`, and the phase's `contracts/<slug>/`
on the pinned base. Do not use `.devin/skills/hrms-phase-workflow`: this is
a **managed parent/child run**, not a dynamic workflow. The auth service
owns session context (ARCH-03); salary-module precedes employee-service
(ARCH-01). P0 auth must settle before P1.

**Assignment:** freeze the phase's OpenAPI endpoints/DTOs, authorities,
statuses, `ApiError.code` numeric legacy error values, documented intentional
divergences and module/proxy flags. Check that
`frontend/src/generated/validation-schema.json` is an export from
`hrms-validation` (never hand-edit generated output). Match error codes to
COMPONENT_MAPPING.md §11. Verify contract tests/schema snapshot. If already
correct, record a pinned SHA without an empty PR. For an unambiguous
in-scope contract defect, change only the contract-owned sources, generate
artifacts with the repository generator, run focused lint/tests, open a PR
to the provided base and report its SHA. Escalate any semantic change to
the parent rather than making one silently. Do not rewrite later phases
or claim Oracle/Forms/CDC/calendar evidence without running it.

**Return via `provide_structured_output` (all fields required):**
```json
{
  "phase": "<P0–P5>", "role": "contract",
  "status": "success|failed|blocked",
  "base_ref": "<ref>", "base_sha": "<40-hex>",
  "contract_ref": "<ref>", "contract_sha": "<40-hex>",
  "pr_url": "<full URL or null>", "artifacts": ["<file or URL>"],
  "authorities": ["<authority>"], "error_codes": ["<numeric code: meaning>"],
  "schema_module": "<generator and exported artifact>",
  "divergences": ["<declared behavior>"], "evidence_gaps": ["<not run>"],
  "blockers": ["<action needed>"]
}
```
`success` means a frozen technical contract, not permission for live cutover.
Include actual commands and their results in the session report.
