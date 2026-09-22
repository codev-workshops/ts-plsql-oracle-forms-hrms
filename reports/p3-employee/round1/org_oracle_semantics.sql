-- Oracle semantics: CONNECT BY over ALL rows (START WITH manager null), WHERE status filter applied AFTER; CONNECT_BY_ISLEAF counts any child
with recursive org as (
  select e.emp_id, e.manager_emp_id, e.employment_status, 1 org_level, ' > '||e.first_name||' '||e.last_name org_path, array[e.emp_id] visited
  from employees e where e.manager_emp_id is null
  union all
  select c.emp_id, c.manager_emp_id, c.employment_status, p.org_level+1, p.org_path||' > '||c.first_name||' '||c.last_name, p.visited||c.emp_id
  from employees c join org p on c.manager_emp_id=p.emp_id where not (c.emp_id = any(p.visited)))
, ora as (select emp_id, org_level, org_path, case when exists (select 1 from employees k where k.manager_emp_id=o.emp_id) then 0 else 1 end is_leaf from org o where employment_status='ACTIVE')
, pg as (
with recursive org as (
    select e.emp_id, e.manager_emp_id, 1 as org_level, ' > ' || e.first_name || ' ' || e.last_name as org_path, array[e.emp_id] as visited
    from employees e where e.employment_status = 'ACTIVE' and e.manager_emp_id is null
    union all
    select c.emp_id, c.manager_emp_id, p.org_level + 1, p.org_path || ' > ' || c.first_name || ' ' || c.last_name, p.visited || c.emp_id
    from employees c join org p on c.manager_emp_id = p.emp_id where c.employment_status = 'ACTIVE' and not (c.emp_id = any (p.visited)))
select o.emp_id, o.org_level, o.org_path,
       case when exists (select 1 from employees k where k.manager_emp_id = o.emp_id and k.employment_status = 'ACTIVE' and not (k.emp_id = any (o.visited))) then 0 else 1 end as is_leaf
from org o)
select coalesce(ora.emp_id,pg.emp_id) emp_id, ora.org_level ora_level, pg.org_level pg_level, ora.is_leaf ora_leaf, pg.is_leaf pg_leaf, ora.org_path ora_path, pg.org_path pg_path
from ora full join pg on ora.emp_id=pg.emp_id
where ora.emp_id is null or pg.emp_id is null or ora.org_level<>pg.org_level or ora.is_leaf<>pg.is_leaf or ora.org_path<>pg.org_path order by 1;
