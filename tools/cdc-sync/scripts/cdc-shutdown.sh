#!/usr/bin/env bash
# Phase 5 (CUTOVER_PLAN.md §9.2 "Reference-data ownership"): stop the one-way reference-table
# replication Oracle -> PostgreSQL once the admin UI on PostgreSQL owns the reference tables.
#
# Order matters (rule 6, tools/cdc-sync/README.md):
#   1. drain   – wait until the source connector has no lag (LogMiner offset == current SCN)
#   2. gate    – `cdc-sync checksum --group reference` must be 0 (Oracle == PostgreSQL)
#   3. stop    – delete the JDBC sink first (nothing can be written after this), then the source
#   4. record  – write the final SCN + checksum report next to the extract (final-oracle-extract.sh)
#
# untested-live: no Oracle / Kafka Connect in the backend environment; exercised by
# CdcDecommissionScriptsTest (syntax + dry-run) and by the integration session.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
SOURCE="${SOURCE_CONNECTOR:-hrms-oracle-source}"
SINK="${SINK_CONNECTOR:-hrms-postgres-sink}"
OUT_DIR="${OUT_DIR:-./decommission-evidence}"
DRY_RUN="${DRY_RUN:-false}"
JAR="${CDC_SYNC_JAR:-$(dirname "$0")/../target/hrms-tool-cdc-sync.jar}"

: "${ORACLE_URL:?jdbc:oracle:thin:@//host:1521/HRMSPDB}"
: "${ORACLE_USER:?}"
: "${ORACLE_PASSWORD:?}"
: "${PG_URL:?jdbc:postgresql://host:5432/hrms}"
: "${PG_USER:?}"
: "${PG_PASSWORD:?}"

mkdir -p "$OUT_DIR"
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT_DIR/cdc-shutdown.log"; }
redact() { printf '%s' "$*" | sed -e "s#${ORACLE_PASSWORD}#\*\*\*#g" -e "s#${PG_PASSWORD}#\*\*\*#g"; }
run() { if [ "$DRY_RUN" = "true" ]; then log "DRY-RUN: $(redact "$@")"; else "$@"; fi; }

log "1/4 drain: waiting for $SOURCE to reach the current SCN"
for attempt in $(seq 1 60); do
  state=$(curl -fsS "$CONNECT_URL/connectors/$SOURCE/status" | jq -r '.tasks[0].state // "UNKNOWN"' || echo UNKNOWN)
  lag=$(curl -fsS "$CONNECT_URL/connectors/$SOURCE/offsets" 2>/dev/null | jq -r '.offsets[0].offset.lag_ms // 0' || echo 0)
  log "   state=$state lag_ms=$lag"
  [ "$DRY_RUN" = "true" ] && break
  if [ "$state" = "RUNNING" ] && [ "${lag:-0}" -lt 1000 ]; then break; fi
  [ "$attempt" = 60 ] && { log "source did not drain"; exit 2; }
  sleep 5
done

log "2/4 gate: checksum --group reference"
run java -jar "$JAR" checksum --group reference \
  --oracle "$ORACLE_URL" --oracle-user "$ORACLE_USER" --oracle-password "$ORACLE_PASSWORD" \
  --pg "$PG_URL" --pg-user "$PG_USER" --pg-password "$PG_PASSWORD" | tee "$OUT_DIR/reference-checksum.txt"

log "3/4 stop: sink then source (a stopped sink cannot write; a stopped source cannot mine)"
run curl -fsS -X DELETE "$CONNECT_URL/connectors/$SINK"
run curl -fsS -X DELETE "$CONNECT_URL/connectors/$SOURCE"
for c in "$SINK" "$SOURCE"; do
  if [ "$DRY_RUN" != "true" ] && curl -fsS "$CONNECT_URL/connectors/$c" >/dev/null 2>&1; then
    log "connector $c still registered"; exit 3
  fi
done

log "4/4 record: final SCN"
if [ "$DRY_RUN" = "true" ]; then
  echo "DRY-RUN" > "$OUT_DIR/final-scn.txt"
else
  printf 'set heading off feedback off\nselect current_scn from v$database;\nexit\n' \
    | sqlplus -s "$ORACLE_USER/$ORACLE_PASSWORD@${ORACLE_TNS:-HRMSPDB}" | tr -d '[:space:]' > "$OUT_DIR/final-scn.txt"
fi
log "CDC stopped; final SCN $(cat "$OUT_DIR/final-scn.txt"). Next: final-oracle-extract.sh"
