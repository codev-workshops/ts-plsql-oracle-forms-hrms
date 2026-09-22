#!/bin/bash
# Reset PostgreSQL to pristine seed + parallel-run fixtures and (re)start the backend.
set -u
cd /home/ubuntu/repos/ts-plsql-oracle-forms-hrms
pkill -f auth-0.1.0-SNAPSHOT.jar; sleep 2
docker exec hrms-pg psql -q -U hrms -d hrms -c "drop schema public cascade; create schema public;" >/dev/null
# first boot: Flyway creates schema, then PayElementStartupValidator aborts (no seed yet)
bash /home/ubuntu/start-backend.sh > /home/ubuntu/backend-flyway.log 2>&1
for f in 01_reference_data 02_employee_data 03_transaction_data 04_user_accounts; do
  docker exec -i hrms-pg psql -q -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/fixtures/pg/$f.sql >/dev/null || echo "SEED FAIL $f"
done
docker exec -i hrms-pg psql -q -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/parallel-run/fixtures/payroll.sql >/dev/null
docker exec -i hrms-pg psql -q -v ON_ERROR_STOP=1 -U hrms -d hrms < tools/parallel-run/fixtures/leave.sql >/dev/null
echo "seeded"
