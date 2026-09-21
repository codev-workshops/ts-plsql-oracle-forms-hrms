-- PostgreSQL equivalent of HRMS.VW_ORG_HIERARCHY (CONNECT BY -> WITH RECURSIVE).
-- Cycle guard: the visited-path array stops the walk when an emp_id re-appears (legacy
-- CONNECT BY would raise ORA-01436; here the cyclic edge is simply not expanded).
-- ORDER SIBLINGS BY LAST_NAME is reproduced by carrying a sort key of padded last names.
with recursive org as (
    select e.emp_id, e.emp_number,
           e.first_name || ' ' || e.last_name                    as emp_name,
           e.manager_emp_id, e.dept_id,
           1                                                     as org_level,
           ' > ' || e.first_name || ' ' || e.last_name           as org_path,
           array[e.emp_id]                                       as visited,
           array[rpad(e.last_name, 50)]                          as sort_key
    from employees e
    where e.employment_status = 'ACTIVE'
      and e.manager_emp_id is null
    union all
    select c.emp_id, c.emp_number,
           c.first_name || ' ' || c.last_name,
           c.manager_emp_id, c.dept_id,
           p.org_level + 1,
           p.org_path || ' > ' || c.first_name || ' ' || c.last_name,
           p.visited || c.emp_id,
           p.sort_key || rpad(c.last_name, 50)
    from employees c
    join org p on c.manager_emp_id = p.emp_id
    where c.employment_status = 'ACTIVE'
      and not (c.emp_id = any (p.visited))
)
select o.emp_id, o.emp_number, o.emp_name, o.manager_emp_id, o.dept_id,
       o.org_level, o.org_path,
       case when exists (select 1 from employees k
                         where k.manager_emp_id = o.emp_id
                           and k.employment_status = 'ACTIVE'
                           and not (k.emp_id = any (o.visited)))
            then 0 else 1 end                                    as is_leaf
from org o
order by o.sort_key, o.emp_id;
