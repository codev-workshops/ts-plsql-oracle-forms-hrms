---
name: hrms-phase-workflow
description: Devin dynamic workflow that runs each HRMS modernization phase (P0–P5) as contract → parallel backend+frontend sessions → fan-in → integration-test session → phase gate, with stacked PRs and manual-approval nodes for calendar gates.
---

# hrms-phase-workflow

Run with the `run_workflow` tool, passing the contents of `workflow.py` from this
directory as `script` and a stable `run_id` (e.g. `hrms-phases-2026q3`).

Configuration and the resume procedure (calendar gates, halts) are documented in
[WORKFLOW_README.md](../../../WORKFLOW_README.md) §8. Environment variables:
`HRMS_WF_BASE_BRANCH`, `HRMS_WF_PHASES`, `HRMS_WF_APPROVED_GATES`.
