-- PostgreSQL equivalent of HRMS.VW_PAYROLL_LATEST.
select pd.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name                         as emp_name,
       pp.period_name,
       sum(case when pd.element_type = 'EARNING' then pd.amount else 0 end)                      as gross_pay,
       sum(case when pd.element_type = 'TAX' then abs(pd.amount) else 0 end)                     as total_taxes,
       sum(case when pd.element_type in ('DEDUCTION', 'BENEFIT') then abs(pd.amount) else 0 end) as total_deductions,
       sum(pd.amount)                                             as net_pay
from payroll_details pd
join employees    e  on pd.emp_id = e.emp_id
join payroll_runs pr on pd.run_id = pr.run_id
join pay_periods  pp on pr.period_id = pp.period_id
where pr.run_id = (select max(pr2.run_id) from payroll_runs pr2 where pr2.status = 'APPROVED')
  and pd.status <> 'ERROR'
group by pd.emp_id, e.emp_number, e.first_name || ' ' || e.last_name, pp.period_name
order by pd.emp_id;
