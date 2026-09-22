"""Level 3 post-write check (golden-oracle OFF): key-matched (emp_id) comparison of the PG
reconciliation queries against tests/golden/views-baseline.csv (pristine seed), plus
semantic invariants for VW_ORG_HIERARCHY / VW_EMPLOYEE_COMPENSATION on rows created by the
scripted writes (Playwright stage 2 + parallel-run)."""
import csv, subprocess, collections, sys, re
from decimal import Decimal, InvalidOperation
def canon(v):
    try: return format(Decimal(v).normalize(), 'f')
    except (InvalidOperation, ValueError): return v
REPO = '/home/ubuntu/repos/ts-plsql-oracle-forms-hrms'
AS_OF = '2024-06-30'
VIEWS = {'VW_ACTIVE_EMPLOYEES': 'vw_active_employees.sql',
         'VW_ORG_HIERARCHY': 'vw_org_hierarchy.sql',
         'VW_EMPLOYEE_COMPENSATION': 'vw_employee_compensation.sql'}

def psql(sql):
    out = subprocess.run(['docker','exec','-i','hrms-pg','psql','-U','hrms','-d','hrms','-At','-F','\x1f','-v','ON_ERROR_STOP=1'],
                         input=sql, capture_output=True, text=True)
    if out.returncode: sys.exit(out.stderr)
    return [l.split('\x1f') for l in out.stdout.splitlines()]

def run_view(f):
    sql = open(f'{REPO}/tests/reconciliation/pg/{f}').read()
    sql = re.sub(r'^--.*$', '', sql, flags=re.M).replace(':as_of', f"date '{AS_OF}'")
    # get column names via a LIMIT 0 on a wrapper
    hdr = subprocess.run(['docker','exec','-i','hrms-pg','psql','-U','hrms','-d','hrms','-A','-F','\x1f','-v','ON_ERROR_STOP=1'],
                         input=f"select * from ({sql.rstrip().rstrip(';')}) q limit 0;", capture_output=True, text=True).stdout.splitlines()[0].split('\x1f')
    rows = psql(sql)
    return [c.upper() for c in hdr], rows

base = collections.defaultdict(lambda: collections.defaultdict(dict))
for r in csv.DictReader(open(f'{REPO}/tests/golden/views-baseline.csv')):
    base[r['view']][int(r['row_no'])][r['column']] = r['value']
report = ['# Level 3 post-write keyed check (PG only, as_of %s)' % AS_OF, '']
problems = []
touched = {1: 'salary scenarios (L2) closed the seed row, new row effective 2030', 12: 'terminated by L2 employee.terminate.*', 21: 'terminated by e2e stage 2 (session-revocation)'}
for view, f in VIEWS.items():
    cols, rows = run_view(f)
    actual = {}
    for r in rows:
        d = dict(zip(cols, r)); actual[int(d['EMP_ID'])] = d
    bkey = {int(row['EMP_ID']): row for row in base[view].values()}
    removed = sorted(set(bkey) - set(actual)); added = sorted(set(actual) - set(bkey))
    changed = []
    for k in sorted(set(bkey) & set(actual)):
        for c, v in bkey[k].items():
            av = actual[k].get(c, '')
            av = '\\N' if av == '' else av
            if canon(av) != canon(v):
                changed.append((k, c, v, av))
    report += [f'## {view}: baseline {len(bkey)} rows, actual {len(actual)} rows',
               f'- removed emp_ids: {removed}', f'- added emp_ids (scripted writes): {added}',
               f'- changed cells on common emp_ids: {len(changed)}']
    for k, c, v, av in changed:
        note = touched.get(k, 'UNEXPLAINED')
        if note == 'UNEXPLAINED' and view == 'VW_ORG_HIERARCHY' and c == 'IS_LEAF': note = 'manager of a new scripted employee (leaf -> non-leaf)'
        if note == 'UNEXPLAINED': problems.append(f'{view} emp {k} {c}: {v} -> {av}')
        report.append(f'  - emp {k} {c}: `{v}` -> `{av}`  ({note})')
    for k in removed:
        if k not in touched: problems.append(f'{view}: emp {k} dropped (subtree of terminated manager) - legacy CONNECT BY applies the ACTIVE filter after traversal and keeps this row; tests/reconciliation/pg/vw_org_hierarchy.sql filters inside the recursion')
        report.append(f'  - removed emp {k}: {touched.get(k, "UNEXPLAINED")}')

# invariants on new rows
report += ['', '## Semantic invariants (all active employees, incl. scripted rows)']
inv = {
 'VW_EMPLOYEE_COMPENSATION: exactly one active salary row per active employee having any salary':
   "select count(*) from (select emp_id, count(*) c from salary_records where active_flag='Y' group by emp_id having count(*)<>1) x",
 'salary_records: closed rows have end_date = next effective_date (contract half-open interval; declared divergence from legacy effective-1) and active_flag=N':
   """select count(*) from salary_records a join salary_records b on a.emp_id=b.emp_id and b.effective_date>a.effective_date
      and not exists (select 1 from salary_records m where m.emp_id=a.emp_id and m.effective_date>a.effective_date and m.effective_date<b.effective_date)
      where not (a.active_flag='N' and a.end_date = b.effective_date)""",
 'VW_EMPLOYEE_COMPENSATION: compa_ratio = round(base/midpoint*100,1) for every row':
   """select count(*) from (%s) v where v.compa_ratio <> round(v.base_salary/v.grade_midpoint*100,1)""",
 'VW_ORG_HIERARCHY: org_level = manager org_level + 1 and org_path = manager path || name':
   """with v as (%s) select count(*) from v c join v p on c.manager_emp_id=p.emp_id
      where c.org_level<>p.org_level+1 or c.org_path <> p.org_path || ' > ' || c.emp_name""",
 'VW_ORG_HIERARCHY: is_leaf=0 iff an active report exists':
   """with v as (%s) select count(*) from v where is_leaf <> case when exists (select 1 from employees k where k.manager_emp_id=v.emp_id and k.employment_status='ACTIVE') then 0 else 1 end""",
 'VW_ORG_HIERARCHY: every ACTIVE employee reachable from a root (no orphan/cycle)':
   """with v as (%s) select count(*) from employees e where e.employment_status='ACTIVE' and not exists (select 1 from v where v.emp_id=e.emp_id)""",
 'Terminated employees (12, 21, e2e A) absent from all three views':
   "select count(*) from employees e where employment_status='TERMINATED' and exists (select 1 from (%s) v where v.emp_id=e.emp_id)",
}
def q(f):
    s = open(f'{REPO}/tests/reconciliation/pg/{f}').read()
    return re.sub(r'^--.*$', '', s, flags=re.M).replace(':as_of', f"date '{AS_OF}'").rstrip().rstrip(';')
for name, sql in inv.items():
    if '%s' in sql:
        src = 'vw_org_hierarchy.sql' if 'ORG' in name else 'vw_employee_compensation.sql' if 'COMPENSATION' in name else 'vw_active_employees.sql'
        sql = sql % q(src)
    n = int(psql(sql)[0][0])
    report.append(f'- {"PASS" if n == 0 else "FAIL"} ({n} violating rows): {name}')
    if n: problems.append(f'invariant violated: {name} ({n} rows)')

# name-case divergence (legacy PKG_EMPLOYEE.create_employee UPPER(TRIM(name)))
rows = psql("select emp_id, first_name, last_name from employees where emp_id>=10000 and (first_name<>upper(first_name) or last_name<>upper(last_name)) order by 1")
report += ['', '## Legacy name normalisation (PKG_EMPLOYEE.create_employee/update_employee store UPPER(TRIM(name)))',
           f'- scripted rows stored with mixed case in target: {rows}']
if rows: problems.append(f'name case: legacy would store UPPER names; target keeps as typed -> FULL_NAME/EMP_NAME/ORG_PATH/MANAGER_NAME differ for {[r[0] for r in rows]}')
report += ['', '## Result: ' + ('**PASS**' if not problems else '**MISMATCH**'), ''] + [f'- {p}' for p in problems]
open('/home/ubuntu/it-reports/reconcile-post-writes-keyed.md', 'w').write('\n'.join(report) + '\n')
print('\n'.join(report))
