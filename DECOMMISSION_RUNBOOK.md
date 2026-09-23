# Phase 5 decommission runbook – Oracle Forms, WebLogic, Oracle HRMS

Operational companion to [CUTOVER_PLAN.md](CUTOVER_PLAN.md) §9 (work items 9.2, rollback 9.3,
acceptance 9.4). Every step names its evidence; the evidence directory produced by the scripts is
archived with the XML exports. **Status in this repository: `untested-live`** – no Oracle,
WebLogic or Kafka Connect instance is available to the modernisation team (GOLDEN-ORACLE MODE =
OFF); the scripts are exercised offline only (`tools/cdc-sync` `CdcDecommissionScriptsTest`,
`bash -n` + `DRY_RUN=true`). The integration session executes them live.

## 0. Preconditions (§9.1)

| # | Check | Evidence |
|---|---|---|
| 0.1 | Phases 1–4 exited: every table group has PostgreSQL as database of record (`cdc-sync checksum --group <g>` = 0 for `performance`, `leave`, `employee`, `payroll`) | `checksum-<group>.txt` |
| 0.2 | `PKG_PERFORMANCE`, `PKG_LEAVE`, `PKG_EMPLOYEE`, `PKG_PAYROLL` already dropped | `legacy-objects.csv` from step 3 |
| 0.3 | P5 backend deployed: `reporting`, `integration`, `admin` modules mounted; `GET /api/integration/status` answers for the three feeds | deployment log |
| 0.4 | P5 gate green: Level 1 (`backend` `mvn verify`), Level 2 (`tools/parallel-run --only reporting,integration`, `legacy_source=recorded`), Level 3 (`tools/reconcile compare` against `tests/golden/views-baseline.csv`, and the VAL-05 re-baseline `tests/golden/views-baseline-p5-leave-summary.csv`) | CI run links |

## 1. Reporting cut-over (read-only, §9.3 rollback = re-point consumers)

1. Flip report consumers (frontend `reports` module, scheduled CSV consumers) to
   `/api/reports/*` – JSON and `.csv` aliases are in the frozen contract.
2. Keep `PKG_REPORTING` callable for the 30-day observation window; it is dropped in step 5.
3. Rollback: re-point the consumer. No data is written by reporting.

## 2. Reference-data ownership – stop CDC (`tools/cdc-sync/scripts/cdc-shutdown.sh`)

The admin module on PostgreSQL is the only writer of `DEPARTMENTS`, `LOCATIONS`, `JOB_GRADES`,
`JOB_TITLES`, `LEAVE_TYPES`, `HOLIDAYS`, `SYSTEM_PARAMETERS` (Flyway `V8`, ARCH-01). The one-way
Oracle → PostgreSQL replication (§2 rule 1) is therefore stopped:

```bash
export CONNECT_URL=http://connect:8083 ORACLE_URL=... ORACLE_USER=... ORACLE_PASSWORD=... \
       PG_URL=... PG_USER=... PG_PASSWORD=... OUT_DIR=/archive/hrms-decommission
tools/cdc-sync/scripts/cdc-shutdown.sh
```

| Step | What | Gate |
|---|---|---|
| 1/4 drain | source connector lag < 1 s | log line `state=RUNNING lag_ms=…` |
| 2/4 gate | `cdc-sync checksum --group reference` | exit 0 → `reference-checksum.txt` |
| 3/4 stop | delete sink **then** source connector | both `GET /connectors/<name>` return 404 |
| 4/4 record | `select current_scn from v$database` | `final-scn.txt` |

Rollback (before step 4 of this runbook only): re-`POST` `debezium/hrms-oracle-source.json` and
`hrms-postgres-sink.json` with `snapshot.mode=no_data`; rows written on PostgreSQL in between are
pushed back with `cdc-sync reverse --group reference --since <flip instant> --apply`.
From now on the Oracle reference tables are stale by design; Forms users (if any remain) see
stale reference data – which is why step 3 must follow within the same change window.

## 3. Final Oracle extract (`tools/cdc-sync/scripts/final-oracle-extract.sh`)

Read-only; can be repeated. Produces, in `OUT_DIR`:

| File | Source | Purpose |
|---|---|---|
| `hrms_final_<date>.dmp` + `.log` (in `DATA_PUMP_DIR`) | `expdp schemas=HRMS flashback_time=systimestamp` | full schema + data archive |
| `views-final-oracle.csv` | `tools/reconcile capture --oracle … --as-of <date>` | last output of the six `VW_*` views – the Oracle leg that `tests/golden/views-baseline.csv` was never able to capture (tests/golden/README.md). Commit it as the Oracle-side baseline if the diff below is empty; any difference is a finding against the PostgreSQL pack, not the seed. |
| `views-final-reconcile.md` | `tools/reconcile compare --pg … --baseline views-final-oracle.csv` | Level 3 final full pass (§9.4 "Data reconciliation") |
| `checksum-<group>.txt` × 6 | `cdc-sync checksum` per `TableGroup` | all `0` |
| `legacy-objects.csv` | `USER_OBJECTS` | drop list for step 5 |
| `MANIFEST.sha256` | `sha256sum` of everything above | archive integrity |

## 4. Forms / WebLogic shutdown (§9.3: 30 + 30 days)

1. **Observe 30 days**: proxy access logs show *zero* hits on the legacy Forms routes
   (`/forms/*`, the SSO exchange `POST /api/auth/sso/exchange` returns `SSO_MODULE_NOT_LEGACY` for
   every module – `sso.exchange.*` scenarios). Evidence: log query + count = 0.
2. Remove the proxy routes to Forms; remove `USER_SESSIONS` write-through (`PKG_SECURITY` has no
   live callers – §9.1).
3. Stop the WebLogic Forms managed servers and the admin server; **do not delete** for a further
   30 days (rollback = start them again; the Oracle instance is still up until step 5).
4. Revoke the Forms application database account (`ALTER USER … ACCOUNT LOCK`) – a locked account
   is the evidence that nothing connects; unlock is the rollback.

## 5. Oracle decommission (after step 4's second 30 days)

In this order, each a separate, logged `sqlplus` session, using `legacy-objects.csv`:

1. `DROP TRIGGER` every `TRG_*`.
2. `DROP PACKAGE` `PKG_SECURITY`, `PKG_REPORTING`, `PKG_INTEGRATION`, `PKG_COMMON`, `PKG_AUDIT`,
   `PKG_VALIDATION`, `PKG_NOTIFICATION` (bodies first).
3. `DROP VIEW` the six `VW_*` (their last output is in `views-final-oracle.csv`).
4. Confirm `USER_OBJECTS` lists no `PACKAGE`, `TRIGGER`, `VIEW`, `PROCEDURE`, `FUNCTION`.
5. Shut the instance down (`SHUTDOWN IMMEDIATE`), keep the Data Pump export + archived redo for
   the retention period, then release the host.

## 6. PostgreSQL clean-up

After step 5 only: any legacy-only column retirement (e.g. the Oracle-format `ssn_encrypted`
payload once the re-encryption plan of MODERNIZATION_BLUEPRINT.md is complete) happens via a new
Flyway migration, never by hand. The benefits feed never emits more than `***-**-dddd`
(SEC-05/SEC-11), so no consumer depends on the legacy payload.

## 7. Repository (§9.4 exit)

* Move `plsql/` and `forms/` to `legacy/` with a README pointer (they remain the characterisation
  reference for `tools/parallel-run` recorded expectations).
* Remove `tools/cdc-sync/debezium/*.json` from the deployment; keep in `legacy/` for the reverse
  extract history.
* Exit evidence: no Forms process running, no PL/SQL anywhere in the target (`rg -i
  'plpgsql|create trigger|call pkg_' backend/` empty), instance decommissioned.

## Rollback matrix

| Step | Rollback | Window |
|---|---|---|
| 1 reporting | re-point consumers to Oracle Reports / ref cursors | until step 5 |
| 2 CDC stop | re-register connectors; `cdc-sync reverse --apply` for the gap | until step 4.4 |
| 3 extract | none needed (read-only) | – |
| 4 Forms | restart WebLogic, unlock account, restore proxy routes | 30 days |
| 5 Oracle | Data Pump import into a new instance | retention period |
