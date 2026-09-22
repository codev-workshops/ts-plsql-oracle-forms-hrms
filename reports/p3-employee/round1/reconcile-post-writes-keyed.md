# Level 3 post-write keyed check (PG only, as_of 2024-06-30)

## VW_ACTIVE_EMPLOYEES: baseline 23 rows, actual 29 rows
- removed emp_ids: [12, 21]
- added emp_ids (scripted writes): [10001, 10002, 10003, 10004, 10006, 10007, 10008, 10011]
- changed cells on common emp_ids: 3
  - emp 1 CURRENT_SALARY: `450000` -> `\N`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 CURRENCY_CODE: `USD` -> `\N`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 PAY_FREQUENCY: `MONTHLY` -> `\N`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - removed emp 12: terminated by L2 employee.terminate.*
  - removed emp 21: terminated by e2e stage 2 (session-revocation)
## VW_ORG_HIERARCHY: baseline 23 rows, actual 25 rows
- removed emp_ids: [12, 21, 22, 23, 24]
- added emp_ids (scripted writes): [10001, 10002, 10003, 10004, 10007, 10008, 10011]
- changed cells on common emp_ids: 0
  - removed emp 12: terminated by L2 employee.terminate.*
  - removed emp 21: terminated by e2e stage 2 (session-revocation)
  - removed emp 22: UNEXPLAINED
  - removed emp 23: UNEXPLAINED
  - removed emp 24: UNEXPLAINED
## VW_EMPLOYEE_COMPENSATION: baseline 23 rows, actual 29 rows
- removed emp_ids: [12, 21]
- added emp_ids (scripted writes): [10001, 10002, 10003, 10004, 10006, 10007, 10008, 10011]
- changed cells on common emp_ids: 5
  - emp 1 BASE_SALARY: `450000` -> `480000.00`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 COMPA_RATIO: `100` -> `106.7`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 SALARY_EFFECTIVE_DATE: `2023-01-01` -> `2032-01-01`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 LAST_CHANGE_REASON: `Annual review` -> `MERIT`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - emp 1 LAST_CHANGE_PCT: `\N` -> `4.35`  (salary scenarios (L2) closed the seed row, new row effective 2030)
  - removed emp 12: terminated by L2 employee.terminate.*
  - removed emp 21: terminated by e2e stage 2 (session-revocation)

## Semantic invariants (all active employees, incl. scripted rows)
- PASS (0 violating rows): VW_EMPLOYEE_COMPENSATION: exactly one active salary row per active employee having any salary
- FAIL (1 violating rows): salary_records: closed rows have end_date = next effective_date (contract half-open interval; declared divergence from legacy effective-1) and active_flag=N
- PASS (0 violating rows): VW_EMPLOYEE_COMPENSATION: compa_ratio = round(base/midpoint*100,1) for every row
- PASS (0 violating rows): VW_ORG_HIERARCHY: org_level = manager org_level + 1 and org_path = manager path || name
- PASS (0 violating rows): VW_ORG_HIERARCHY: is_leaf=0 iff an active report exists
- FAIL (4 violating rows): VW_ORG_HIERARCHY: every ACTIVE employee reachable from a root (no orphan/cycle)
- PASS (0 violating rows): Terminated employees (12, 21, e2e A) absent from all three views

## Legacy name normalisation (PKG_EMPLOYEE.create_employee/update_employee store UPPER(TRIM(name)))
- scripted rows stored with mixed case in target: [['10001', 'Grace', 'Hopper'], ['10002', 'Grace', 'Hopper'], ['10003', 'Grace', 'Hopper'], ['10004', 'Ada', 'Lovelace'], ['10005', 'Grace', 'Hopper'], ['10006', 'Ada', 'Lovelace']]

## Result: **MISMATCH**

- VW_ORG_HIERARCHY: emp 22 dropped (subtree of terminated manager) - legacy CONNECT BY applies the ACTIVE filter after traversal and keeps this row; tests/reconciliation/pg/vw_org_hierarchy.sql filters inside the recursion
- VW_ORG_HIERARCHY: emp 23 dropped (subtree of terminated manager) - legacy CONNECT BY applies the ACTIVE filter after traversal and keeps this row; tests/reconciliation/pg/vw_org_hierarchy.sql filters inside the recursion
- VW_ORG_HIERARCHY: emp 24 dropped (subtree of terminated manager) - legacy CONNECT BY applies the ACTIVE filter after traversal and keeps this row; tests/reconciliation/pg/vw_org_hierarchy.sql filters inside the recursion
- invariant violated: salary_records: closed rows have end_date = next effective_date (contract half-open interval; declared divergence from legacy effective-1) and active_flag=N (1 rows)
- invariant violated: VW_ORG_HIERARCHY: every ACTIVE employee reachable from a root (no orphan/cycle) (4 rows)
- name case: legacy would store UPPER names; target keeps as typed -> FULL_NAME/EMP_NAME/ORG_PATH/MANAGER_NAME differ for ['10001', '10002', '10003', '10004', '10005', '10006']
