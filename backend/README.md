# HRMS backend (modernization target)

Java 21 + Spring Boot 3 + Flyway + PostgreSQL 16 (checkpoint D-00, D-10).

```bash
mvn -f backend/pom.xml test     # applies every migration + dev seed to an embedded PostgreSQL 16 (no Docker)
```

- Schema migrations: `src/main/resources/db/migration/V<n>__<desc>.sql` (one global version sequence).
- Dev/test seed: `src/main/resources/db/seed/R__dev_seed.sql`, generated from `data/seed/*.sql` by
  `python3 backend/scripts/port_seed.py` (run from the repository root). Loaded only with the `dev`
  Spring profile or in tests.
- Runtime: `HRMS_DB_URL`, `HRMS_DB_USER` (migration owner), `HRMS_DB_PASSWORD`, `HRMS_APP_ROLE`
  (default `hrms_app`, the NOLOGIN role whose members the application connects as).
