# tools/cdc-sync – Oracle ⇄ PostgreSQL synchronisation

Implements CUTOVER_PLAN.md §2 rule 6 for every table group (one owner per group, rule 1).

| Step | Tool | Notes |
|---|---|---|
| (i) bulk load | `java -jar target/hrms-tool-cdc-sync.jar bulk-load --group <flag>` | `AS OF SCN` snapshot → keyed upsert; sequences advanced; checksum gate |
| (ii) bake CDC | Debezium Oracle connector (`debezium/hrms-oracle-source.json`) + JDBC sink (`debezium/hrms-postgres-sink.json`) | LogMiner, `online_catalog`; reference tables only in P0 (rule 1) |
| (iii) flip | proxy flag, then `java -jar target/hrms-tool-cdc-sync.jar checksum` | connector `table.include.list` shrinks by the flipped group |
| (iv) reverse extract | `java -jar target/hrms-tool-cdc-sync.jar reverse --group <flag> --since <flip instant>` | dry-run by default (nightly rehearsal); `--apply` on rollback |

Decision (item 0.5b): **Debezium** over GoldenGate – open source, Kafka Connect deployment already
available to the platform team, identical `precise` decimal handling to the target's `numeric`
columns. The `ExtractNewRecordState` transform + JDBC sink `upsert` mode reproduce exactly the
`insert … on conflict do update` used by the bulk loader, so a CDC-replicated row and a bulk-loaded
row are byte-identical (verified by `checksum`).

Type/identifier rules are in `TypeMapping` (upper-case → lower-case unquoted identifiers;
`NUMBER(p,0)` → `bigint`/`integer`; `DATE` → `timestamp(0)`; `CHAR(1) Y/N` flags round-trip
unchanged; `''` → `NULL` on the way back to Oracle).

Round-trip smoke test on `HOLIDAYS` (`HolidaysRoundTripTest`) runs against Testcontainers
PostgreSQL only: it loads the frozen fixture, computes the checksum, runs the reverse-extract SQL
generator and re-applies through the PostgreSQL side to prove idempotence. The Oracle leg is
executed by the integration session (no Oracle in the backend environment).
