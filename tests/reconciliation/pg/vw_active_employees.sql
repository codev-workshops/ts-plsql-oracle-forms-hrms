-- PostgreSQL equivalent of HRMS.VW_ACTIVE_EMPLOYEES (a.k.a. "employee directory" in the plan docs).
-- Ordering key for reconciliation: emp_id. The date reference is a bind (:as_of) so both
-- sides are evaluated at the same instant; tools/reconcile passes the same value to Oracle.
select e.emp_id, e.emp_number, e.first_name, e.last_name,
       e.first_name || ' ' || e.last_name                         as full_name,
       e.email, e.phone_work, e.phone_mobile,
       e.hire_date,
       -- Oracle MONTHS_BETWEEN(:as_of, hire_date) / 12 truncated to one decimal: whole months
       -- when both days-of-month match or both are month ends, otherwise the day difference
       -- contributes (day(as_of) - day(hire_date)) / 31. age() would drop that fraction.
       trunc(((
           (extract(year from cast(:as_of as date)) - extract(year from e.hire_date)) * 12
           + (extract(month from cast(:as_of as date)) - extract(month from e.hire_date))
           + case
               when extract(day from cast(:as_of as date)) = extract(day from e.hire_date) then 0
               when extract(day from cast(:as_of as date))
                      = extract(day from date_trunc('month', cast(:as_of as date)) + interval '1 month - 1 day')
                    and extract(day from e.hire_date)
                      = extract(day from date_trunc('month', e.hire_date) + interval '1 month - 1 day')
                 then 0
               else (extract(day from cast(:as_of as date)) - extract(day from e.hire_date)) / 31.0
             end
         ) / 12)::numeric, 1)                                       as tenure_years,
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
