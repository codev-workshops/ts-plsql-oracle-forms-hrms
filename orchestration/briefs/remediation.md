# Remediation child — copy and fill all inputs before dispatch

**Repository:** `codev-workshops/ts-plsql-oracle-forms-hrms`. **Phase:**
`<P0–P5 / slug>`. **Mode:** `<code|rollback>`. **Owner:**
`<backend|frontend>` (code mode). **Parent session:** `<URL>`.
**Frozen contract:** `<ref>@<SHA>`. **Owning implementation branch/SHA
and target PR:** `<refs/URL>`. **Merged integration SHA/report:**
`<SHA/URL>`. **Failure and reproduction:** `<paste precise integration
finding>`. **Required rerun checks:** `<L1,L2,L3,e2e,phase-specific>`.
**Playbook source ref:** `<ref containing ORCHESTRATION_PLAYBOOK.md>`.

You are a standalone child with your own VM. Read `CUTOVER_PLAN.md`
§§1–2 and your phase's §4–9, `TEST_STRATEGY.md` §§2, 4–5, 7,
`COMPONENT_MAPPING.md` §§1–8, 11, `MODERNIZATION_BLUEPRINT.md`
§§8, 10, `ORCHESTRATION_PLAYBOOK.md` and the integration report. If the
playbook is absent, use `git show <playbook source ref>:ORCHESTRATION_PLAYBOOK.md`.
Do not use a dynamic workflow. Auth owns session context (ARCH-03);
P3 salary-module precedes employee-service (ARCH-01).

**Code mode:** add a regression test on the owning backend **or**
frontend branch before fixing the reported defect. Change only that
side; preserve the frozen contract and all gates. Re-run focused tests
and lint/typecheck, commit a new SHA and update the existing PR. If
ownership is `contract` or `environment`, stop for parent/human action
instead of relaxing checks or inventing production evidence. Parent
rebuilds integration from the new SHA and spawns a **new** integration
child to rerun all checks.

**Rollback mode (only with explicit live operator authorization):**
**Operator authorization/evidence:** `<approver URL>`.
**Module, table group, reverse-extract runbook, SCN and checksum:**
`<values/URLs>`. Do not start any production operation without these.
Freeze affected traffic/writes; perform PostgreSQL → Oracle reverse
extract of **all** post-flip writes, verify counts/checksums; set the
module proxy flag back to `LEGACY` (also `payroll.engine=LEGACY` for P4);
re-enable Oracle → PostgreSQL CDC; verify Forms/SSO and single writer.
For P2 restore exactly one accrual scheduler. Follow CUTOVER_PLAN.md
§2 rule 6 and module-specific rollback section. If any step fails,
halt and report partial state **immediately**, not a successful rollback.
Never drop old packages or treat git revert as reverse data migration.

**Return via `provide_structured_output` (all fields required):**
```json
{
  "phase": "<P0–P5>", "role": "remediation",
  "mode": "code|rollback", "status": "success|failed|blocked",
  "failure_owner": "backend|frontend|contract|environment",
  "base_sha": "<40-hex>", "contract_sha": "<40-hex>",
  "head_ref": "<ref>", "head_sha": "<40-hex>",
  "pr_url": "<full URL or null>",
  "regression_test": "<name/result or not applicable to rollback>",
  "rerun_required": ["level1", "level2", "level3", "e2e", "phase_specific"],
  "rollback_steps": ["<step, evidence, verdict; empty in code mode>"],
  "artifacts": ["<log or result URL>"], "evidence_gaps": ["<missing>"],
  "blockers": ["<action needed>"]
}
```
`success` in code mode means the fix's scoped checks pass, **not** the
integration gate; only the parent may decide that after a fresh run.
