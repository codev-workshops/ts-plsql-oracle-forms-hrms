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

## Dev credentials (OQ-15, provisional)

- `src/main/resources/db/seed/R__dev_seed_p3.sql` gives every seeded ACTIVE employee the password
  **`Hrms#2026`** (bcrypt) and current-year leave balances (PTO 10, SICK 5). Log in with the employee's
  email, e.g. `james.richardson@company.com` (all permissions) or `alex.taylor@company.com` (employee).
- Development and test only: `db/seed` is on the Flyway path only with the `dev` profile or in tests.
  The production path (`db/migration` alone) never creates credentials; P8's data migration gives each
  ACTIVE employee an unusable password and a one-time reset link instead.

## Jobs

`hrms.jobs.enabled=true` turns on the monthly leave accrual (`hrms.jobs.leave-accrual-cron`, default
`0 0 1 1 * *`, 01:00 UTC on the 1st) and the notification dispatcher
(`hrms.jobs.notification-delay`, default `PT5M`). Off by default; run them on one instance only.
