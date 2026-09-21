-- PostgreSQL equivalent of HRMS.VW_ACTIVE_EMPLOYEES (a.k.a. "employee directory" in the plan docs).
-- Ordering key for reconciliation: emp_id. The date reference is a bind (:as_of) so both
-- sides are evaluated at the same instant; tools/reconcile passes the same value to Oracle.
select e.emp_id, e.emp_number, e.first_name, e.last_name,
       e.first_name || ' ' || e.last_name                         as full_name,
       e.email, e.phone_work, e.phone_mobile,
       e.hire_date,
       trunc(((extract(year from age(:as_of, e.hire_date)) * 12
             + extract(month from age(:as_of, e.hire_date))) / 12.0)::numeric, 1) as tenure_years,
       e.employment_type, e.employment_status,
       e.dept_id, d.dept_name, d.dept_code, d.cost_center,
       e.job_id, j.job_title, j.job_code,
       g.grade_id, g.grade_name,
       e.manager_emp_id,
       m.first_name || ' ' || m.last_name                         as manager_name,
       e.location_code,
       l.location_name, l.city, l.state_province, l.country_code,
       sr.base_salary                                             as current_salary,
       sr.currency_code, sr.pay_frequency
from employees e
join departments d on e.dept_id = d.dept_id
join job_titles  j on e.job_id  = j.job_id
join job_grades  g on j.grade_id = g.grade_id
left join employees m on e.manager_emp_id = m.emp_id
left join locations l on e.location_code = l.location_code
left join salary_records sr on e.emp_id = sr.emp_id
    and sr.active_flag = 'Y'
    and sr.effective_date <= :as_of
    and (sr.end_date is null or sr.end_date > :as_of)
where e.employment_status = 'ACTIVE'
  and e.active_flag = 'Y'
order by e.emp_id;
