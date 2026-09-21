-- PostgreSQL equivalent of HRMS.VW_PENDING_APPROVALS.
-- Oracle NUMBER || VARCHAR2 uses the shortest canonical numeric text (e.g. 2.5 -> '2.5', 3 -> '3');
-- numeric(5,1)::text would give '3.0', so total_days is normalised through trim_scale().
select 'LEAVE'                                                     as approval_type,
       lr.request_id                                               as item_id,
       lr.approver_emp_id                                          as approver_id,
       e.first_name || ' ' || e.last_name                          as requestor_name,
       lt.leave_type_name                                          as item_description,
       lr.created_date                                             as request_date,
       trim_scale(lr.total_days)::text || ' day(s) '
           || to_char(lr.start_date, 'MM/DD') || '-' || to_char(lr.end_date, 'MM/DD') as details
from leave_requests lr
join employees   e  on lr.emp_id = e.emp_id
join leave_types lt on lr.leave_type_id = lt.leave_type_id
where lr.status = 'PENDING'
union all
select 'PERFORMANCE',
       pr.review_id,
       pr.reviewer_emp_id,
       e.first_name || ' ' || e.last_name,
       'Performance Review - ' || rc.cycle_name,
       pr.created_date,
       pr.status
from performance_reviews pr
join employees     e  on pr.emp_id = e.emp_id
join review_cycles rc on pr.cycle_id = rc.cycle_id
where pr.status = 'MANAGER_REVIEW'
order by 1, 2;
