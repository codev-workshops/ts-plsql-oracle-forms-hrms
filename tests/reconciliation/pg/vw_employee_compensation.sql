-- PostgreSQL equivalent of HRMS.VW_EMPLOYEE_COMPENSATION.
-- Oracle ROUND(x, 1) on NUMBER is half-away-from-zero; PostgreSQL round(numeric, 1) matches.
select e.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name                         as emp_name,
       d.dept_name, j.job_title, g.grade_name,
       sr.base_salary,
       g.min_salary                                               as grade_min,
       g.max_salary                                               as grade_max,
       (g.min_salary + g.max_salary) / 2                          as grade_midpoint,
       round(sr.base_salary / ((g.min_salary + g.max_salary) / 2) * 100, 1) as compa_ratio,
       sr.effective_date                                          as salary_effective_date,
       sr.change_reason                                           as last_change_reason,
       sr.change_pct                                              as last_change_pct
from employees e
join departments d on e.dept_id = d.dept_id
join job_titles  j on e.job_id = j.job_id
join job_grades  g on j.grade_id = g.grade_id
join salary_records sr on e.emp_id = sr.emp_id and sr.active_flag = 'Y'
where e.employment_status = 'ACTIVE'
order by e.emp_id, sr.effective_date;
