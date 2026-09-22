# Parallel run – Level 2

Result: **FAIL** (63/67 scenarios, 3 deferred)

| scenario | expected (contract) | legacy | target | verdict |
|---|---|---|---|---|
| auth.login.ok | ok {emp_id=2} | n/a (recorded) | ok {emp_id=2} | PASS |
| auth.login.unknown-user | `-20301` | n/a (recorded) | `-20301` | PASS |
| auth.login.wrong-password | `-20301` | n/a (recorded) | `-20301` | PASS |
| auth.refresh.rotates | ok {tokenType=Bearer} | n/a (recorded) | ok {tokenType=Bearer} | PASS |
| auth.password.too-short | `-20310` | n/a (recorded) | `-20310` | PASS |
| auth.password.no-upper | `-20311` | n/a (recorded) | `-20311` | PASS |
| auth.password.no-digit | `-20312` | n/a (recorded) | `-20312` | PASS |
| auth.lockout.after-5-failures | `RATE_LIMITED` | n/a (recorded) (documented divergence, expects `-20301`) | `RATE_LIMITED` | PASS |
| sso.exchange.legacy-module | `SSO_LEGACY_UNAVAILABLE` | n/a (untested-live) (with Oracle attached expects ok {formsModule=HRMS_EMPLOYEE}) | `SSO_MODULE_NOT_LEGACY` | TARGET-DIFF |
| sso.exchange.new-module-rejected | `SSO_MODULE_NOT_LEGACY` | n/a (recorded) | `SSO_MODULE_NOT_LEGACY` | PASS |
| performance.cycle.create | ok {status=DRAFT, cycleName=Parallel-run cycle} | n/a (recorded) | ok {status=DRAFT, cycleName=Parallel-run cycle} | PASS |
| performance.cycle.open.not-draft | `-20401` | n/a (recorded) | `-20401` | PASS |
| performance.cycle.close.draft | `-20401` | n/a (recorded) | `-20401` | PASS |
| performance.review.self-assessment.wrong-status | `-20402` | n/a (recorded) | `-20402` | PASS |
| performance.review.manager-review.wrong-status | `-20402` | n/a (recorded) | `-20402` | PASS |
| performance.review.acknowledge.wrong-status | `-20402` | n/a (recorded) | `-20402` | PASS |
| performance.review.manager-review.rating-out-of-range | `-20403` | n/a (recorded) | `-20403` | PASS |
| performance.goal.progress-100-completes | ok {status=COMPLETED} | n/a (recorded) | ok {status=COMPLETED} | PASS |
| performance.cycle.generate-reviews.row-count | ok {generated=22, skipped=0} | n/a (recorded) | ok {generated=22, skipped=0} | PASS |
| performance.cycle.generate-reviews.idempotent | ok {generated=0, skipped=22} | n/a (recorded) | ok {generated=0, skipped=22} | PASS |
| leave.submit.ok | ok {status=PENDING, totalDays=5} | n/a (recorded) | ok {status=PENDING, totalDays=5} | PASS |
| leave.submit.overlap | `-20202` | n/a (recorded) | `-20202` | PASS |
| leave.submit.insufficient-balance | `-20201` | n/a (recorded) | `-20201` | PASS |
| leave.submit.invalid-leave-type | `-20203` | n/a (recorded) | `-20203` | PASS |
| leave.submit.tenure-not-met | `-20203` | n/a (recorded) | `-20203` | PASS |
| leave.submit.date-order | `-20210` | n/a (recorded) | `-20210` | PASS |
| leave.submit.too-far-in-past | `-20211` | n/a (recorded) | `-20211` | PASS |
| leave.submit.no-business-days | `-20212` | n/a (recorded) | `-20212` | PASS |
| leave.submit.half-day.am-pm-same-day.bug-06 | ok {status=PENDING} | n/a (recorded) (documented divergence, expects `-20202`) | ok {status=PENDING} | PASS |
| leave.business-days.saturday-holiday.bug-05 | ok {businessDays=4} | n/a (recorded) (documented divergence, expects ok {businessDays=5}) | ok {businessDays=4} | PASS |
| leave.cancel.pending | ok {status=CANCELLED} | n/a (recorded) | ok {status=CANCELLED} | PASS |
| leave.cancel.pending.balance-restored | ok {pending=0, available=10, used=0} | n/a (recorded) | ok {pending=0, available=10, used=0} | PASS |
| leave.cancel.approved.balance-restored | ok {pending=0, available=10, used=0} | n/a (recorded) | ok {pending=0, available=10, used=0} | PASS |
| leave.cancel.cancelled.invalid-status | `-20204` | n/a (recorded) | `-20204` | PASS |
| leave.approve.ok | ok {status=APPROVED, approverEmpId=1} | n/a (recorded) | ok {status=APPROVED, approverEmpId=1} | PASS |
| leave.approve.moves-pending-to-used | ok {pending=0, used=5} | n/a (recorded) | ok {pending=0, used=5} | PASS |
| leave.approve.not-pending | `-20204` | n/a (recorded) | `-20204` | PASS |
| leave.reject.ok.releases-pending | ok {pending=0, used=0} | n/a (recorded) | ok {pending=0, used=0} | PASS |
| leave.reject.status | ok {status=REJECTED} | n/a (recorded) | ok {status=REJECTED} | PASS |
| leave.reject.comments-required | `VALIDATION_FAILED` | n/a (recorded) | `VALIDATION_FAILED` | PASS |
| leave.reject.not-pending | `-20204` | n/a (recorded) | `-20204` | PASS |
| leave.request.not-found | `LEAVE_REQUEST_NOT_FOUND` | n/a (recorded) (documented divergence, expects `ORA-01403`) | `LEAVE_REQUEST_NOT_FOUND` | PASS |
| leave.batch.accrual.seed-year | `HTTP_404` | n/a (recorded) (endpoint mounted in P5; legacy expects ok {accrued=8.75, available=10.75}) | `HTTP_404` | DEFERRED |
| leave.batch.carryover.seed-year | `HTTP_404` | n/a (recorded) (endpoint mounted in P5; legacy expects ok {carryoverFromPrev=5, openingBalance=5}) | `HTTP_404` | DEFERRED |
| leave.batch.carryover.expire.bug-04 | `HTTP_404` | n/a (recorded) (endpoint mounted in P5; legacy expects ok {carryoverFromPrev=0, adjustment=-5}) | `HTTP_404` | DEFERRED |
| salary.create.ok | ok {active=true, changePct=2.22, baseSalary=460000.00} | n/a (recorded) | ok {active=true, changePct=2.22, baseSalary=460000.00} | PASS |
| salary.change.closes-prior | ok {[1].endDate=2030-01-01, [1].active=false} | n/a (recorded) (documented divergence, expects ok {[1].endDate=2029-12-31, [1].active=false}) | ok {[1].endDate=2030-01-01, [1].active=false} | PASS |
| salary.create.not-positive | `-20101` | n/a (recorded) | `-20101` | PASS |
| salary.change.audit-rows | ok {active=true, baseSalary=480000.00} | n/a (recorded) | ok {active=true, baseSalary=480000.00} | PASS |
| employee.create.number-from-sequence | ok {email=pr.create@company.com, employmentStatus=ACTIVE, active=true, version=0} | n/a (recorded) | ok {email=pr.create@company.com, employmentStatus=ACTIVE, active=true, version=0} | PASS |
| employee.create.names-upper-trimmed | ok {firstName=GRACE, lastName=HOPPER} | n/a (recorded) | ok {firstName=GRACE, lastName=HOPPER} | PASS |
| employee.update.ok | ok {firstName=RENAMED, version=1} | n/a (recorded) | ok {firstName=RENAMED, version=1} | PASS |
| employee.terminate.ok | ok {employmentStatus=TERMINATED, active=false, terminationDate=2025-06-30} | n/a (recorded) | ok {employmentStatus=TERMINATED, active=false, terminationDate=2025-06-30} | PASS |
| employee.terminate.already-terminated | `-20005` | n/a (recorded) | `-20005` | PASS |
| employee.terminate.session-revoked | `TOKEN_INVALID` | n/a (recorded) (documented divergence, expects ok {empId=12}) | `TOKEN_INVALID` | PASS |
| employee.transfer.not-active | `-20012` | n/a (recorded) | `-20012` | PASS |
| employee.transfer.ok-same-dept-writes-history | ok {[0].changeType=TRANSFER} | n/a (recorded) | ok {[0].changeType=TRANSFER} | PASS |
| employee.validate.names-required | `-20010` | n/a (recorded) | `-20010` | PASS |
| employee.validate.invalid-department | `-20003` | n/a (recorded) | `-20003` | PASS |
| employee.validate.invalid-job | `-20011` | n/a (recorded) | `-20011` | PASS |
| employee.validate.invalid-manager | `-20004` | n/a (recorded) | `-20004` | PASS |
| employee.validate.manager-cycle | `-20004` | n/a (recorded) | `-20004` | PASS |
| employee.validate.salary-not-positive | `-20101` | n/a (recorded) | `-20101` | PASS |
| employee.trigger.hire-date-too-far | `-20501` | n/a (recorded) | `-20501` | PASS |
| employee.trigger.email-in-use | `-20502` | n/a (recorded) | `-20502` | PASS |
| employee.trigger.no-reactivation | `-20503` | n/a (recorded) | `-20503` | PASS |
| employee.trigger.no-physical-delete | `-20504` | n/a (recorded) | `-20504` | PASS |
