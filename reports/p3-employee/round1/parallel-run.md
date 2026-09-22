# Parallel run – Level 2

Result: **FAIL** (20/21 scenarios)

| scenario | expected (contract) | legacy | target | verdict |
|---|---|---|---|---|
| salary.create.ok | ok {changePct=2.22, active=true, baseSalary=460000.00} | n/a (recorded) | ok {changePct=2.22, active=true, baseSalary=460000.00} | PASS |
| salary.change.closes-prior | ok {[1].endDate=2030-01-01, [1].active=false} | n/a (recorded) (documented divergence, expects ok {[1].endDate=2029-12-31, [1].active=false}) | ok {[1].endDate=2030-01-01, [1].active=false} | PASS |
| salary.create.not-positive | `-20101` | n/a (recorded) | `-20101` | PASS |
| salary.change.audit-rows | ok {active=true, baseSalary=480000.00} | n/a (recorded) | ok {active=true, baseSalary=480000.00} | PASS |
| employee.create.number-from-sequence | ok {employmentStatus=ACTIVE, email=pr.create@company.com, version=0, active=true} | n/a (recorded) | ok {employmentStatus=ACTIVE, email=pr.create@company.com, version=0, active=true} | PASS |
| employee.update.ok | ok {version=1, firstName=RENAMED} | n/a (recorded) | ok {version=1, firstName=RENAMED} | PASS |
| employee.terminate.ok | ok {active=false, employmentStatus=TERMINATED, terminationDate=2025-06-30} | n/a (recorded) | ok {active=false, employmentStatus=TERMINATED, terminationDate=2025-06-30} | PASS |
| employee.terminate.already-terminated | `-20005` | n/a (recorded) | `-20005` | PASS |
| employee.terminate.session-revoked | `TOKEN_INVALID` | n/a (recorded) (documented divergence, expects ok {empId=12}) | `TOKEN_INVALID` | PASS |
| employee.transfer.not-active | `-20012` | n/a (recorded) | `-20012` | PASS |
| employee.transfer.ok-same-dept-writes-history | ok {[0].changeType=TRANSFER} | n/a (recorded) | ok {[0].changeType=HIRE} | TARGET-DIFF |
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
