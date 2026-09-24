-- PostgreSQL VW_LEAVE_SUMMARY equivalent *after* the Phase 5 re-baseline (CUTOVER_PLAN.md §9.2,
-- contracts/p5-reporting-decommission VAL-05): AVAILABLE subtracts PENDING, matching PKG_LEAVE /
-- leave_balances.available and GET /api/reports/leave-summary.available. The legacy-faithful
-- expression stays in ../pg/vw_leave_summary.sql (it is the P0 baseline oracle and the API's
-- legacyAvailable); this pack is compared against tests/golden/views-baseline-p5-leave-summary.csv.
select e.emp_id, e.emp_number,
       e.first_name || ' ' || e.last_name                         as emp_name,
       d.dept_name,
       lt.leave_type_name,
       lb.opening_balance,
       lb.accrued,
       lb.used,
       lb.adjustment,
       lb.pending,
       lb.opening_balance + lb.accrued - lb.used + lb.adjustment - lb.pending as available,
       round(lb.used * 100 / nullif(lb.opening_balance + lb.accrued, 0), 1) as utilization_pct
from leave_balances lb
join employees   e  on lb.emp_id = e.emp_id
join departments d  on e.dept_id = d.dept_id
join leave_types lt on lb.leave_type_id = lt.leave_type_id
where lb.calendar_year = extract(year from cast(:as_of as date))
  and e.employment_status = 'ACTIVE'
order by e.emp_id, lt.leave_type_name;
