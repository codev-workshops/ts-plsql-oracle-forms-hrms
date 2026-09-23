# Managed-session HRMS orchestration

This is an operator playbook, **not an executable workflow**. One long-lived Devin
orchestrator holds the state and makes every spawn, join, gate, remediation and
approval decision. Use `devin_session_create` with a structured-output schema and
`notify_on_response=true`; read each settled child's `structured_output` through
`devin_session_interact(action="get")`. Children have separate machines and do
not inherit the parent conversation, filesystem, branch checkout or credentials.
Never call `run_workflow` or use the approval environment variable described in
[WORKFLOW_README.md](WORKFLOW_README.md) for this run.

The normative sources are [CUTOVER_PLAN.md](CUTOVER_PLAN.md) §§1–10 (particularly
§2 rule 6), [TEST_STRATEGY.md](TEST_STRATEGY.md) §§2, 5, 7,
[COMPONENT_MAPPING.md](COMPONENT_MAPPING.md) §§1–8, 11 and
[MODERNIZATION_BLUEPRINT.md](MODERNIZATION_BLUEPRINT.md) §§8, 10. A previous
dynamic-workflow implementation exists on `phase/p0-foundation` through
`phase/p5-reporting-decommission`; its [cutover logs](cutover-log/) say that
Oracle/Forms/utPLSQL and live CDC were **not run**. Recorded-legacy fixtures and
pre-approved calendar gates are not substitutes for live cross-database comparisons
or elapsed production periods. The managed run must independently obtain those
results; until then its gate is `BLOCKED`, not `PASS`.

## State and ordering

Keep a durable orchestration ledger (session IDs/URLs, phase, stage, base and
reported head SHAs, PR URLs, frozen-contract SHA, integration report link,
L1/L2/L3/e2e/phase-specific verdicts, failure owner, remediation round, evidence
mode, approval evidence and next action). Record it in the parent session and
update it after **each** settled child; on resumption verify branch heads still
match the ledger before spawning dependent work. Never infer completion from a
child's PR alone or from prose if structured output is missing.

| Phase | Entry and hard-order constraint | Source | TEST_STRATEGY.md §5 gate |
|---|---|---|---|
| P0 Foundation | First: auth owns session context (ARCH-03); proxy + SSO bridge before any React page goes live. Oracle golden oracle, PostgreSQL Flyway/seed, shared modules, app shell, CDC and reverse-extract tooling. | CUTOVER_PLAN.md §§1, 2, 4; COMPONENT_MAPPING.md §§1, 2, 7, 8 | Auth/JWT/role/schema tests; live SSO → Forms and re-encryption equality; both seed loads, six views, utPLSQL and CDC `HOLIDAYS` smoke; security sign-off. |
| P1 Performance | Only after **P0 gate**; first domain cutover. | CUTOVER_PLAN.md §5; COMPONENT_MAPPING.md §6 | State-machine JUnit; legacy-vs-REST review/goal diff; `VW_PENDING_APPROVALS` PERFORMANCE and rating aggregates; 4-week zero-rollback bake. |
| P2 Leave | After P1 exit; single accrual scheduler. | CUTOVER_PLAN.md §6; COMPONENT_MAPPING.md §5 | Business-calendar/balance JUnit; declared BUG-04/05/06 diffs only; `VW_LEAVE_SUMMARY` (VAL-05 offset = `PENDING`) and `VW_PENDING_APPROVALS` LEAVE; one reconciled accrual cycle. |
| P3 Employee | After P2; **salary-module first**, sole `SALARY_RECORDS` owner (ARCH-01), then employee-service; P0 auth owns context (ARCH-03). | CUTOVER_PLAN.md §7; COMPONENT_MAPPING.md §3 | Concurrency, salary and validation JUnit; package **and Forms direct-DML** diff; `VW_ACTIVE_EMPLOYEES`, `VW_EMPLOYEE_COMPENSATION`, `VW_ORG_HIERARCHY`; read-only then write reconciliation and business-owner hire-date limit. |
| P4 Payroll | After P3 exit; Java engine reads salary-module, not employee-service. | CUTOVER_PLAN.md §8; COMPONENT_MAPPING.md §4 | Tax boundaries/authz/JUnit; `PayrollShadowRunner` legacy Oracle vs Java PostgreSQL per `(RUN, EMP_ID, ELEMENT_ID)`; `VW_PAYROLL_LATEST` to the cent; human shadow gate. |
| P5 Reporting / decommission | After P4 shadow promotion **and** three-period rollback window. | CUTOVER_PLAN.md §9; COMPONENT_MAPPING.md §§7–8 and reporting/integration targets | Report/admin tests; Oracle ref-cursor vs REST row diff; final six-view pass **before** SQL re-baseline; 30-day zero-hit gate before decommission. |

The target Spring Boot application uses PostgreSQL only; Oracle stays the
independent golden oracle / Forms store during coexistence. Never replace L2
with a PostgreSQL-only recorded fixture run or L3 with a single-database
comparison and label it cross-database `PASS`. Declared preserve-vs-fix diffs
must match TEST_STRATEGY.md §4 exactly, not be silently whitelisted.

## Per-phase control loop (run P0, then P1 → P5)

1. **Contract — spawn one child and wait.** Provide repo, phase, base ref/SHA,
   source sections, template and a structured-output schema. Freeze the phase's
   `contracts/<slug>/openapi.yaml`, `error-codes.md` (`ApiError.code` numeric codes,
   HTTP statuses), and `frontend/src/generated/validation-schema.json` **exported**
   from `hrms-validation`. Record the frozen SHA and contract brief before
   spawning implementers; a changed contract invalidates their prior outputs.
   See [contract brief](orchestration/briefs/contract.md).
2. **Implementation — spawn backend and frontend children concurrently** on
   independent branches at that exact contract SHA, giving each the frozen brief
   and target PR base. Backend owns services/entities/Flyway, JUnit Level 1 and
   `tools/parallel-run/` scenarios; frontend owns React, generated-schema
   consumption and Vitest. P3 backend is a *sequence*: salary-module child
   settles first, then employee-service child starts against the salary head;
   frontend may work concurrently from the contract. See
   [backend](orchestration/briefs/backend.md) and
   [frontend](orchestration/briefs/frontend.md).
3. **Join — wait for BOTH implementation reports.** Require `status=success`,
   no blockers, a pinned head SHA, contract conformance, and passing applicable
   L1 tests on both sides (JUnit and Vitest/schema snapshot). A failure here
   routes to its own side's remediation; do **not** start integration on a
   half-built branch. Merge exact SHAs into an integration branch/PR; resolve
   clerical conflicts only and run the merged build. If nothing changed in a
   verification rerun, record the existing immutable SHA instead of creating
   an empty PR.
4. **Integration — spawn one dedicated child in its own session** against the
   merged SHA, not either implementer's checkout. It runs real-stack Playwright
   (React ↔ Spring ↔ PostgreSQL), L1 on the merged tree, L2 contract diff /
   parallel run vs **utPLSQL on Oracle** via `tools/parallel-run/`, and L3
   Oracle `VW_*` vs `tests/reconciliation/pg/` via `tools/reconcile/`.
   P0 substitutes live SSO/Forms and legacy-v2 re-encryption equivalence
   for auth's nonexistent REST-to-legacy contract diff; it additionally
   requires seed/CDC/utPLSQL checks. P4 uses `PayrollShadowRunner` as the L2 shadow
   comparator. See [integration brief](orchestration/briefs/integration.md).
5. **Gate — parent parses structured fields**, not the integration child's
   headline: `L1=pass ∧ L2=pass ∧ L3=pass ∧ e2e=pass ∧ phase_specific=pass
   ∧ failure_owner=none ∧ blockers=[]`. `untested-live`, `deferred`, missing
   artifacts, missing structured output and unknown ownership are **not**
   passes. Integration reports must identify failure side and reproducible
   evidence. On backend/frontend failure, spawn a side-scoped
   [remediation child](orchestration/briefs/remediation.md), join updated
   SHA(s), and **re-spawn** the integration child to rerun all checks (not only
   the failed check). On `both`, remediate both in parallel. On `contract`,
   stop for human contract decision; on `environment`, stop for missing runtime
   or access rather than mislabeling a check green. After three unsuccessful
   remediation rounds, stop and request a decision.
6. **Operations/approval pause.** A green technical gate authorizes asking for
   the separately required live evidence and named human sign-off; it does not
   perform a cutover. Do not start the next phase's contract child until its
   predecessor's operational gate and rollback-window requirements are
   satisfied. Record approver, date, period/log identifiers and evidence URL;
   a statement of pre-approval without elapsed evidence does not close a
   calendar gate. Phase branches are promoted only after these checks; never
   auto-merge the phase stack into `main`.

### Calendar and operational pauses

| Boundary | Evidence and decision required before continuing |
|---|---|
| P0 → P1 | P0 live seed loads on **both** DBs, Oracle schema scratch build (only documented invalid trigger), green Oracle utPLSQL, SSO/Forms, re-encryption and CDC smoke; human security sign-off on auth divergence (CUTOVER_PLAN.md §4.4). |
| P1 → P2 | 4 weeks **after actual** `performance=NEW` cutover, zero rollbacks and nightly reconciliation; human bake sign-off (CUTOVER_PLAN.md §5.5). |
| P2 → P3 | One real monthly accrual cycle reconciled and scheduler handover verified without double accrual (CUTOVER_PLAN.md §6.4). |
| P3 → P4 | Business owner confirms `HR.MAX_FUTURE_HIRE_DAYS` before P3; `NEW_READONLY` read-side pass before writes; 2-week reversible bake (§7.3), at least one week of empty nightly write diffs before trigger retirement (§7.4). |
| P4 shadow → engine/UI promotion | Payroll signs off ≥3 **consecutive real periods**: 2024-rule inputs 0.00 diff, every other difference explained; only then pair `payroll.engine=JAVA` with `payroll=NEW` and the §2 rule 6 migration (CUTOVER_PLAN.md §8.3–8.4). |
| P4 → P5 | Three further production periods on Java/PostgreSQL with rollback window intact, cent-level reconciliation and Payroll's window-closure sign-off before dropping legacy package (§8.4). |
| P5 decommission | 30 days of **observed** zero legacy proxy hits, final six-view comparison/archived export and human sign-off; stop WebLogic but retain it another 30 days before deleting (§9.3–9.4). |

No timer or pre-approval flag simulates these periods. A technical PR may be
ready while the next phase remains paused for weeks; resume the **same parent
session** with the ledger and fresh evidence rather than launching a workflow.

### Live rollback is a data operation, not a git revert

When an authorized operator declares a live cutover rollback, spawn a
**rollback-mode remediation child** with the affected module, flag, table group,
last-good SCN/checksums, runbook and operator authorization. Per CUTOVER_PLAN.md
§2 rule 6: freeze module writes; perform PostgreSQL → Oracle reverse extract
for **all** rows written since the flip (including P4
`PAY_PERIODS`/`PAYROLL_RUNS`/`PAYROLL_DETAILS`); verify row counts/checksums;
set the proxy module flag to `LEGACY` (and `payroll.engine=LEGACY` for P4);
restart Oracle → PostgreSQL CDC; verify Forms/SSO and single-writer ownership.
For P2, disable Spring accrual and restore legacy job exactly once. Report each
step, evidence, and any incomplete step as blocked. Do not drop packages or
re-encryption columns, or flip production flags speculatively. P0 auth's
special fallback is CUTOVER_PLAN.md §4.3; P5 reporting is read-only and
re-points consumers (§9.3).

## Branch/PR ledger

For a new implementation from the planning base, start each
`phase/<slug>` at the **previous approved phase head** (`main` only for P0
if it has the reference docs). Create `<slug>/contract` → PR to
`phase/<slug>`; `<slug>/backend` and `<slug>/frontend` each branch from the
frozen contract and PR to it; `<slug>/integration` merges pinned heads and
PRs to `phase/<slug>`. P3 adds `<slug>/backend-salary` → contract and
`<slug>/backend` → backend-salary. Integration findings go on the integration
PR, not hidden in the parent chat. Only a tested, approved phase head is
fast-forwarded; humans review/merge phase PRs bottom-up. Never force-push
implementation branches or change a frozen contract under running children.

For a **fresh managed verification of this repo's existing P0–P5 code**, do
not reset or overwrite historical `phase/*` and `<slug>/*` branches. Pin
`phase/p5-reporting-decommission` as the audit base and create
`managed/<run-id>/<slug>/{contract,backend,frontend,integration}` only for
actual changes, with PRs into a `managed/<run-id>/<slug>` phase branch
anchored at that base; use existing SHA + child report when no diff exists.
The P3 salary branch precedes employee as above. Integration PRs target
the managed phase branch; remediation appends commits to the failing side.
If the previous phase is blocked, retain reports but do not advance to the
next phase or treat an old `phase/*` promotion as a current approval.

Every child prompt supplies this repository and specific branch/SHA, frozen
contract brief, required source sections, allowed files, target PR branch,
validation command/evidence expectations and its [role template](orchestration/briefs/).
Use the shared structured-output envelope described there; the parent keeps
child URLs, head SHAs, PR links and technical/operational evidence distinct.
