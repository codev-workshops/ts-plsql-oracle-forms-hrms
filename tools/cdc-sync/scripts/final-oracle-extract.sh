#!/usr/bin/env bash
# Phase 5 (CUTOVER_PLAN.md §9.2 "Decommission"): final read-only export of the legacy Oracle
# schema before the instance is decommissioned.
#
#   a. schema + data      – Data Pump export (CONSISTENT, read-only) of the HRMS schema
#   b. six VW_* views     – last output captured with tools/reconcile as the Oracle-side golden
#                           (tests/golden/views-baseline.csv provenance, tests/golden/README.md)
#   c. reference checksum – tools/cdc-sync checksum for every table group (all must be 0)
#   d. inventory          – PKG_*/TRG_*/VW_* object list with status, for the drop step
#   e. manifest           – sha256 of everything written, to be archived with the XML exports
#
# untested-live: exercised by CdcDecommissionScriptsTest (syntax + DRY_RUN) only.
set -euo pipefail

OUT_DIR="${OUT_DIR:-./decommission-evidence}"
DRY_RUN="${DRY_RUN:-false}"
AS_OF="${AS_OF:-$(date -u +%F)}"
RECONCILE_JAR="${RECONCILE_JAR:-$(dirname "$0")/../../reconcile/target/hrms-tool-reconcile.jar}"
CDC_JAR="${CDC_SYNC_JAR:-$(dirname "$0")/../target/hrms-tool-cdc-sync.jar}"
DP_DIR="${DATA_PUMP_DIR:-DATA_PUMP_DIR}"

: "${ORACLE_URL:?}"; : "${ORACLE_USER:?}"; : "${ORACLE_PASSWORD:?}"
: "${PG_URL:?}"; : "${PG_USER:?}"; : "${PG_PASSWORD:?}"

mkdir -p "$OUT_DIR"
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT_DIR/final-extract.log"; }
redact() { printf '%s' "$*" | sed -e "s#${ORACLE_PASSWORD}#\*\*\*#g" -e "s#${PG_PASSWORD}#\*\*\*#g"; }
run() { if [ "$DRY_RUN" = "true" ]; then log "DRY-RUN: $(redact "$@")"; else "$@"; fi; }
sql() {
  if [ "$DRY_RUN" = "true" ]; then log "DRY-RUN sqlplus: $1"; else
    printf 'set heading off feedback off pagesize 0 linesize 400 trimspool on\n%s\nexit\n' "$1" \
      | sqlplus -s "$ORACLE_USER/$ORACLE_PASSWORD@${ORACLE_TNS:-HRMSPDB}"
  fi
}

log "a. Data Pump export (read-only, consistent) -> $DP_DIR/hrms_final_${AS_OF}.dmp"
run expdp "$ORACLE_USER/$ORACLE_PASSWORD@${ORACLE_TNS:-HRMSPDB}" \
  schemas=HRMS directory="$DP_DIR" dumpfile="hrms_final_${AS_OF}.dmp" \
  logfile="hrms_final_${AS_OF}.log" flashback_time=systimestamp

log "b. last output of the six VW_* views (Oracle leg of tests/golden/views-baseline.csv)"
run java -jar "$RECONCILE_JAR" capture \
  --oracle "$ORACLE_URL" --oracle-user "$ORACLE_USER" --oracle-password "$ORACLE_PASSWORD" \
  --as-of "$AS_OF" --out "$OUT_DIR/views-final-oracle.csv"
run java -jar "$RECONCILE_JAR" compare \
  --pg "$PG_URL" --pg-user "$PG_USER" --pg-password "$PG_PASSWORD" \
  --as-of "$AS_OF" --baseline "$OUT_DIR/views-final-oracle.csv" --report "$OUT_DIR/views-final-reconcile.md"

log "c. checksum per table group"
for group in reference performance leave employee payroll cross-cutting; do
  run java -jar "$CDC_JAR" checksum --group "$group" \
    --oracle "$ORACLE_URL" --oracle-user "$ORACLE_USER" --oracle-password "$ORACLE_PASSWORD" \
    --pg "$PG_URL" --pg-user "$PG_USER" --pg-password "$PG_PASSWORD" | tee "$OUT_DIR/checksum-$group.txt"
done

log "d. legacy object inventory (drop list for the runbook)"
sql "select object_type||','||object_name||','||status from user_objects
     where object_type in ('PACKAGE','PACKAGE BODY','TRIGGER','VIEW','PROCEDURE','FUNCTION')
     order by 1;" > "$OUT_DIR/legacy-objects.csv"

log "e. manifest"
( cd "$OUT_DIR" && find . -type f ! -name MANIFEST.sha256 -print0 | sort -z | xargs -0 sha256sum ) > "$OUT_DIR/MANIFEST.sha256"
log "done: $(wc -l < "$OUT_DIR/MANIFEST.sha256") files in $OUT_DIR"
