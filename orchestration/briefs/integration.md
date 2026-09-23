# Integration child — copy and fill all inputs before dispatch

**Repository:** `codev-workshops/ts-plsql-oracle-forms-hrms`. **Phase:**
`<P0–P5 / slug>`. **Parent session:** `<URL>`. **Frozen contract SHA:**
`<SHA>`. **Merged integration branch/SHA:** `<ref>@<SHA>`.
**Backend and frontend heads:** `<ref>@<SHA>, <ref>@<SHA>`.
**P3 salary head:** `<ref>@<SHA or not applicable>`.
**Legacy Oracle and PostgreSQL runtime/fixture provenance:** `<endpoints,
not secrets; label if absent>`. **Phase-specific expected divergences and
views:** `<paste contract brief and TEST_STRATEGY.md §5 row>`.

You are a standalone child in your own session/VM; check out **exactly**
the merged SHA, verify both reported heads and frozen contract are present.
Read `CUTOVER_PLAN.md` §§1–2 and your phase's §4–9,
`TEST_STRATEGY.md` §§2, 4–5, 7, `COMPONENT_MAPPING.md` §§1–8, 11,
`MODERNIZATION_BLUEPRINT.md` §§8, 10 and `ORCHESTRATION_PLAYBOOK.md`.
Do not run a dynamic workflow or treat old cutover-log approvals as current
results. Auth first; ARCH-01 salary-module before employee write flow;
ARCH-03 auth service owns session context.

Execute Level 1 on the merged tree (JUnit, Vitest, schema snapshot),
Playwright **real stack** React ↔ Spring ↔ PostgreSQL, Level 2
`tools/parallel-run/` against live legacy Oracle/utPLSQL and REST on
PostgreSQL, and Level 3 `tools/reconcile/`/`tests/reconciliation/pg/`
against the phase's Oracle `VW_*` views and PG equivalents. Record each
data source, command, exit status and artifacts. Local seed/recorded-legacy
runs may be useful diagnostics but are `untested-live` for cross-system
gates. P0 specifically requires two DB seeds, golden utPLSQL, SSO bridge
into Forms and CDC smoke; P4 uses `PayrollShadowRunner` at `(RUN, EMP_ID,
ELEMENT_ID)` granularity with zero-cent-or-signed-off differences, not
a synthetic-period substitute for three production periods. P5 checks
all six views before any re-baseline.

Do **not** fix code or change production flags here; identify the failing
side with reproduction evidence (`backend`, `frontend`, `both`,
`contract`, `environment`, or `none`). A missing live runtime is
`environment`, not a code defect. Do not mark a calendar sign-off pass
from previous logs.

**Return via `provide_structured_output` (all fields required):**
```json
{
  "phase": "<P0–P5>", "role": "integration",
  "status": "success|failed|blocked", "merged_sha": "<40-hex>",
  "contract_sha": "<40-hex>", "level1": "pass|fail|untested-live",
  "level2": "pass|fail|untested-live", "level3": "pass|fail|untested-live",
  "e2e": "pass|fail|untested-live",
  "phase_specific": "pass|fail|untested-live",
  "failure_owner": "none|backend|frontend|both|contract|environment",
  "findings": ["<command, failure, ownership, reproduction>"],
  "data_provenance": ["<Oracle/PG/fixture and period>"],
  "artifacts": ["<test/diff/reconciliation URL or file>"],
  "evidence_gaps": ["<missing live/calendar check>"],
  "blockers": ["<action needed>"]
}
```
`success` means **all five** checks passed with independent evidence and
no blockers; report a technically green but unsigned calendar gate as an
approval pause to the parent, never as an authorized live flip.
