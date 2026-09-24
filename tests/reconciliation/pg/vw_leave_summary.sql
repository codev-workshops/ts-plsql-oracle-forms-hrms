-- PostgreSQL equivalent of HRMS.VW_LEAVE_SUMMARY.
-- NOTE (documented divergence, TEST_STRATEGY.md §5 row 2): the legacy view's AVAILABLE omits
-- "- PENDING" while PKG_LEAVE and leave_balances.available subtract it. The reconciliation
-- query reproduces the *legacy view* expression so the P0 baseline diff is empty; the
-- "AVAILABLE offset == PENDING" check in P2 compares leave_balances.available separately.
select e.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name                         as emp_name,
       d.dept_name,
       lt.leave_type_name,
       lb.opening_balance,
       lb.accrued,
       lb.used,
       lb.adjustment,
       lb.pending,
       lb.opening_balance + lb.accrued - lb.used + lb.adjustment  as available,
       round(lb.used * 100 / nullif(lb.opening_balance + lb.accrued, 0), 1) as utilization_pct
from leave_balances lb
join employees   e  on lb.emp_id = e.emp_id
join departments d  on e.dept_id = d.dept_id
join leave_types lt on lb.leave_type_id = lt.leave_type_id
where lb.calendar_year = extract(year from cast(:as_of as date))
  and e.employment_status = 'ACTIVE'
order by e.emp_id, lt.leave_type_name;
