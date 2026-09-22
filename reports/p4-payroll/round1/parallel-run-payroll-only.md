# Parallel run – Level 2

Result: **PASS** (6/6 scenarios)

| scenario | expected (contract) | legacy | target | verdict |
|---|---|---|---|---|
| payroll.run.create.closed-period | `-20102` | n/a (recorded) | `-20102` | PASS |
| payroll.run.calculate.seed-period | ok {totalDeductions=82259.64, errorCount=0, totalNet=218573.68, employeeCount=23, status=CALCULATED, totalGross=300833.32} | n/a (recorded) | ok {totalDeductions=82259.64, errorCount=0, totalNet=218573.68, employeeCount=23, status=CALCULATED, totalGross=300833.32} | PASS |
| payroll.shadow.seed-period | ok {summary.unexplained=0, summary.matched=92, summary.javaOnly=0, summary.legacyOnly=0, legacySource=recorded, summary.employees=23} | n/a (recorded) | ok {summary.unexplained=0, summary.matched=92, summary.javaOnly=0, summary.legacyOnly=0, legacySource=recorded, summary.employees=23} | PASS |
| payroll.run.approve.not-calculated | `-20103` | n/a (recorded) | `-20103` | PASS |
| payroll.calculate.no-active-salary | ok {totalElements=1, content[0].errorCode=-20104, content[0].status=ERROR, content[0].elementId=0, content[0].amount=0.00} | n/a (recorded) | ok {totalElements=1, content[0].errorCode=-20104, content[0].status=ERROR, content[0].elementId=0, content[0].amount=0.00} | PASS |
| payroll.payslip.ytd | ok {netPay=21055.44, stateTax=0.00, grossPay=31666.67, ytdGross=52500.00, federalTax=8188.73} | n/a (recorded) | ok {netPay=21055.44, stateTax=0.00, grossPay=31666.67, ytdGross=52500.00, federalTax=8188.73} | PASS |
