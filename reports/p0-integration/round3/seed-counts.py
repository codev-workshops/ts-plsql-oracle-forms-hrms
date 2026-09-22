import re, subprocess, sys, glob
root = "/home/ubuntu/repos/ts-plsql-oracle-forms-hrms"

def counts(files):
    c = {}
    for f in files:
        txt = open(f).read()
        for m in re.finditer(r"insert\s+into\s+([A-Za-z_]+)", txt, re.I):
            t = m.group(1).lower()
            c[t] = c.get(t, 0) + 1
    return c

ora = counts(sorted(glob.glob(f"{root}/tools/fixtures/oracle/0[1-3]_*.sql")))
pgf = counts(sorted(glob.glob(f"{root}/tools/fixtures/pg/0[1-3]_*.sql")))
tables = subprocess.check_output(["docker","exec","hrms-pg","psql","-U","hrms","-d","hrms","-tAc",
    "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not in ('flyway_schema_history') order by 1"]).decode().split()
legacy = set(open('/tmp/legacy_tables.txt').read().split())
rows = []
ok = True
print("| table | oracle fixture inserts | pg fixture inserts | pg actual rows | status |")
print("|---|---:|---:|---:|---|")
for t in tables:
    n = int(subprocess.check_output(["docker","exec","hrms-pg","psql","-U","hrms","-d","hrms","-tAc",f"select count(*) from {t}"]).decode().strip())
    o = ora.get(t); p = pgf.get(t, 0)
    if t not in legacy:
        status = "P0-additive (Flyway V2 / runtime; no legacy counterpart)"
    else:
        if (o or 0) == p == n:
            status = "OK"
        elif (o or 0) == p and t in ("audit_log", "user_sessions", "error_log", "notification_queue"):
            status = "OK (seed identical; extra rows written at runtime by the auth-service during L1/e2e/L2)"
        else:
            status = "MISMATCH"
        if not status.startswith("OK"): ok = False
    print(f"| {t} | {o if o is not None else '-'} | {p} | {n} | {status} |")
print()
print("Legacy tables (schema/tables) compared:", len(legacy), "- present on PG:", len(legacy & set(tables)))
print("Method: Oracle side = INSERT count in tools/fixtures/oracle/*.sql (no Oracle instance, golden-oracle mode OFF); PG side = live count(*) after applying tools/fixtures/pg/*.sql.")
print("Oracle fixture tables absent on PG:", sorted(set(ora) - set(tables)))
print("RESULT:", "PASS" if ok else "FAIL")
