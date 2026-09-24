-- Level 3 scenario: a mid-level manager is terminated while their reports stay active.
-- Applied on top of the pristine seed (tools/fixtures/pg/*.sql) before capturing
-- tests/golden/views-terminated-mid-manager.csv.
--
-- Emp 21 (JENNIFER PARK, dept 20) is the only report of emp 20 (ROBERT KUMAR) and manages
-- emps 22, 23 and 24. Oracle's VW_ORG_HIERARCHY applies WHERE EMPLOYMENT_STATUS = 'ACTIVE'
-- after CONNECT BY, so after this update:
--   * 22/23/24 stay in the view at ORG_LEVEL 5 with ' > JENNIFER PARK' in their ORG_PATH,
--   * 21 herself disappears,
--   * 20 keeps IS_LEAF = 0 (CONNECT_BY_ISLEAF counts the terminated child).
update employees
   set employment_status = 'TERMINATED',
       active_flag       = 'N',
       termination_date  = date '2024-05-31',
       termination_reason = 'VOLUNTARY',
       modified_by       = 'SCENARIO',
       modified_date     = current_timestamp
 where emp_id = 21;

update salary_records
   set end_date = date '2024-05-31'
 where emp_id = 21
   and end_date is null;
