# View reconciliation report

| view | baseline rows | actual rows | mismatching cells |
|---|---:|---:|---:|
| VW_ACTIVE_EMPLOYEES | 0 | 23 | 713 |
| VW_ORG_HIERARCHY | 0 | 23 | 184 |
| VW_EMPLOYEE_COMPENSATION | 0 | 23 | 322 |
| VW_LEAVE_SUMMARY | 0 | 0 | 0 |
| VW_PAYROLL_LATEST | 0 | 0 | 0 |
| VW_PENDING_APPROVALS | 0 | 0 | 0 |

Result: **DIFF**

| view | row | column | baseline | actual |
|---|---:|---|---|---|
| VW_ACTIVE_EMPLOYEES | 1 | CITY | *(absent)* | New York |
| VW_ACTIVE_EMPLOYEES | 1 | COST_CENTER | *(absent)* | CC-1000 |
| VW_ACTIVE_EMPLOYEES | 1 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 1 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 1 | CURRENT_SALARY | *(absent)* | 450000 |
| VW_ACTIVE_EMPLOYEES | 1 | DEPT_CODE | *(absent)* | EXEC |
| VW_ACTIVE_EMPLOYEES | 1 | DEPT_ID | *(absent)* | 1 |
| VW_ACTIVE_EMPLOYEES | 1 | DEPT_NAME | *(absent)* | Executive Office |
| VW_ACTIVE_EMPLOYEES | 1 | EMAIL | *(absent)* | james.richardson@company.com |
| VW_ACTIVE_EMPLOYEES | 1 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 1 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 1 | EMP_ID | *(absent)* | 1 |
| VW_ACTIVE_EMPLOYEES | 1 | EMP_NUMBER | *(absent)* | EMP-000001 |
| VW_ACTIVE_EMPLOYEES | 1 | FIRST_NAME | *(absent)* | JAMES |
| VW_ACTIVE_EMPLOYEES | 1 | FULL_NAME | *(absent)* | JAMES RICHARDSON |
| VW_ACTIVE_EMPLOYEES | 1 | GRADE_ID | *(absent)* | 10 |
| VW_ACTIVE_EMPLOYEES | 1 | GRADE_NAME | *(absent)* | C-Suite |
| VW_ACTIVE_EMPLOYEES | 1 | HIRE_DATE | *(absent)* | 2010-03-15 |
| VW_ACTIVE_EMPLOYEES | 1 | JOB_CODE | *(absent)* | CEO |
| VW_ACTIVE_EMPLOYEES | 1 | JOB_ID | *(absent)* | 1 |
| VW_ACTIVE_EMPLOYEES | 1 | JOB_TITLE | *(absent)* | Chief Executive Officer |
| VW_ACTIVE_EMPLOYEES | 1 | LAST_NAME | *(absent)* | RICHARDSON |
| VW_ACTIVE_EMPLOYEES | 1 | LOCATION_CODE | *(absent)* | HQ |
| VW_ACTIVE_EMPLOYEES | 1 | LOCATION_NAME | *(absent)* | Corporate Headquarters |
| VW_ACTIVE_EMPLOYEES | 1 | MANAGER_EMP_ID | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 1 | MANAGER_NAME | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 1 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 1 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 1 | PHONE_WORK | *(absent)* | 212-555-1001 |
| VW_ACTIVE_EMPLOYEES | 1 | STATE_PROVINCE | *(absent)* | NY |
| VW_ACTIVE_EMPLOYEES | 1 | TENURE_YEARS | *(absent)* | 14.2 |
| VW_ACTIVE_EMPLOYEES | 10 | CITY | *(absent)* | New York |
| VW_ACTIVE_EMPLOYEES | 10 | COST_CENTER | *(absent)* | CC-1200 |
| VW_ACTIVE_EMPLOYEES | 10 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 10 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 10 | CURRENT_SALARY | *(absent)* | 72000 |
| VW_ACTIVE_EMPLOYEES | 10 | DEPT_CODE | *(absent)* | FIN |
| VW_ACTIVE_EMPLOYEES | 10 | DEPT_ID | *(absent)* | 20 |
| VW_ACTIVE_EMPLOYEES | 10 | DEPT_NAME | *(absent)* | Finance & Accounting |
| VW_ACTIVE_EMPLOYEES | 10 | EMAIL | *(absent)* | lisa.wong@company.com |
| VW_ACTIVE_EMPLOYEES | 10 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 10 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 10 | EMP_ID | *(absent)* | 23 |
| VW_ACTIVE_EMPLOYEES | 10 | EMP_NUMBER | *(absent)* | EMP-000023 |
| VW_ACTIVE_EMPLOYEES | 10 | FIRST_NAME | *(absent)* | LISA |
| VW_ACTIVE_EMPLOYEES | 10 | FULL_NAME | *(absent)* | LISA WONG |
| VW_ACTIVE_EMPLOYEES | 10 | GRADE_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 10 | GRADE_NAME | *(absent)* | Mid-Level |
| VW_ACTIVE_EMPLOYEES | 10 | HIRE_DATE | *(absent)* | 2020-11-02 |
| VW_ACTIVE_EMPLOYEES | 10 | JOB_CODE | *(absent)* | ACCT |
| VW_ACTIVE_EMPLOYEES | 10 | JOB_ID | *(absent)* | 52 |
| VW_ACTIVE_EMPLOYEES | 10 | JOB_TITLE | *(absent)* | Accountant |
| VW_ACTIVE_EMPLOYEES | 10 | LAST_NAME | *(absent)* | WONG |
| VW_ACTIVE_EMPLOYEES | 10 | LOCATION_CODE | *(absent)* | HQ |
| VW_ACTIVE_EMPLOYEES | 10 | LOCATION_NAME | *(absent)* | Corporate Headquarters |
| VW_ACTIVE_EMPLOYEES | 10 | MANAGER_EMP_ID | *(absent)* | 21 |
| VW_ACTIVE_EMPLOYEES | 10 | MANAGER_NAME | *(absent)* | JENNIFER PARK |
| VW_ACTIVE_EMPLOYEES | 10 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 10 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 10 | PHONE_WORK | *(absent)* | 212-555-1204 |
| VW_ACTIVE_EMPLOYEES | 10 | STATE_PROVINCE | *(absent)* | NY |
| VW_ACTIVE_EMPLOYEES | 10 | TENURE_YEARS | *(absent)* | 3.5 |
| VW_ACTIVE_EMPLOYEES | 11 | CITY | *(absent)* | New York |
| VW_ACTIVE_EMPLOYEES | 11 | COST_CENTER | *(absent)* | CC-1200 |
| VW_ACTIVE_EMPLOYEES | 11 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 11 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 11 | CURRENT_SALARY | *(absent)* | 48000 |
| VW_ACTIVE_EMPLOYEES | 11 | DEPT_CODE | *(absent)* | FIN |
| VW_ACTIVE_EMPLOYEES | 11 | DEPT_ID | *(absent)* | 20 |
| VW_ACTIVE_EMPLOYEES | 11 | DEPT_NAME | *(absent)* | Finance & Accounting |
| VW_ACTIVE_EMPLOYEES | 11 | EMAIL | *(absent)* | andrew.patel@company.com |
| VW_ACTIVE_EMPLOYEES | 11 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 11 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 11 | EMP_ID | *(absent)* | 24 |
| VW_ACTIVE_EMPLOYEES | 11 | EMP_NUMBER | *(absent)* | EMP-000024 |
| VW_ACTIVE_EMPLOYEES | 11 | FIRST_NAME | *(absent)* | ANDREW |
| VW_ACTIVE_EMPLOYEES | 11 | FULL_NAME | *(absent)* | ANDREW PATEL |
| VW_ACTIVE_EMPLOYEES | 11 | GRADE_ID | *(absent)* | 2 |
| VW_ACTIVE_EMPLOYEES | 11 | GRADE_NAME | *(absent)* | Junior |
| VW_ACTIVE_EMPLOYEES | 11 | HIRE_DATE | *(absent)* | 2022-06-15 |
| VW_ACTIVE_EMPLOYEES | 11 | JOB_CODE | *(absent)* | ACCT-CLK |
| VW_ACTIVE_EMPLOYEES | 11 | JOB_ID | *(absent)* | 62 |
| VW_ACTIVE_EMPLOYEES | 11 | JOB_TITLE | *(absent)* | Accounting Clerk |
| VW_ACTIVE_EMPLOYEES | 11 | LAST_NAME | *(absent)* | PATEL |
| VW_ACTIVE_EMPLOYEES | 11 | LOCATION_CODE | *(absent)* | HQ |
| VW_ACTIVE_EMPLOYEES | 11 | LOCATION_NAME | *(absent)* | Corporate Headquarters |
| VW_ACTIVE_EMPLOYEES | 11 | MANAGER_EMP_ID | *(absent)* | 21 |
| VW_ACTIVE_EMPLOYEES | 11 | MANAGER_NAME | *(absent)* | JENNIFER PARK |
| VW_ACTIVE_EMPLOYEES | 11 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 11 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 11 | PHONE_WORK | *(absent)* | 212-555-1205 |
| VW_ACTIVE_EMPLOYEES | 11 | STATE_PROVINCE | *(absent)* | NY |
| VW_ACTIVE_EMPLOYEES | 11 | TENURE_YEARS | *(absent)* | 2 |
| VW_ACTIVE_EMPLOYEES | 12 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 12 | COST_CENTER | *(absent)* | CC-1300 |
| VW_ACTIVE_EMPLOYEES | 12 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 12 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 12 | CURRENT_SALARY | *(absent)* | 195000 |
| VW_ACTIVE_EMPLOYEES | 12 | DEPT_CODE | *(absent)* | IT |
| VW_ACTIVE_EMPLOYEES | 12 | DEPT_ID | *(absent)* | 30 |
| VW_ACTIVE_EMPLOYEES | 12 | DEPT_NAME | *(absent)* | Information Technology |
| VW_ACTIVE_EMPLOYEES | 12 | EMAIL | *(absent)* | rachel.thompson@company.com |
| VW_ACTIVE_EMPLOYEES | 12 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 12 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 12 | EMP_ID | *(absent)* | 30 |
| VW_ACTIVE_EMPLOYEES | 12 | EMP_NUMBER | *(absent)* | EMP-000030 |
| VW_ACTIVE_EMPLOYEES | 12 | FIRST_NAME | *(absent)* | RACHEL |
| VW_ACTIVE_EMPLOYEES | 12 | FULL_NAME | *(absent)* | RACHEL THOMPSON |
| VW_ACTIVE_EMPLOYEES | 12 | GRADE_ID | *(absent)* | 8 |
| VW_ACTIVE_EMPLOYEES | 12 | GRADE_NAME | *(absent)* | Director |
| VW_ACTIVE_EMPLOYEES | 12 | HIRE_DATE | *(absent)* | 2015-01-05 |
| VW_ACTIVE_EMPLOYEES | 12 | JOB_CODE | *(absent)* | DIR-IT |
| VW_ACTIVE_EMPLOYEES | 12 | JOB_ID | *(absent)* | 20 |
| VW_ACTIVE_EMPLOYEES | 12 | JOB_TITLE | *(absent)* | Director of IT |
| VW_ACTIVE_EMPLOYEES | 12 | LAST_NAME | *(absent)* | THOMPSON |
| VW_ACTIVE_EMPLOYEES | 12 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 12 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 12 | MANAGER_EMP_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 12 | MANAGER_NAME | *(absent)* | MICHAEL OCONNOR |
| VW_ACTIVE_EMPLOYEES | 12 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 12 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 12 | PHONE_WORK | *(absent)* | 312-555-2101 |
| VW_ACTIVE_EMPLOYEES | 12 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 12 | TENURE_YEARS | *(absent)* | 9.4 |
| VW_ACTIVE_EMPLOYEES | 13 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 13 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 13 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 13 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 13 | CURRENT_SALARY | *(absent)* | 145000 |
| VW_ACTIVE_EMPLOYEES | 13 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 13 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 13 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 13 | EMAIL | *(absent)* | kevin.garcia@company.com |
| VW_ACTIVE_EMPLOYEES | 13 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 13 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 13 | EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 13 | EMP_NUMBER | *(absent)* | EMP-000031 |
| VW_ACTIVE_EMPLOYEES | 13 | FIRST_NAME | *(absent)* | KEVIN |
| VW_ACTIVE_EMPLOYEES | 13 | FULL_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 13 | GRADE_ID | *(absent)* | 6 |
| VW_ACTIVE_EMPLOYEES | 13 | GRADE_NAME | *(absent)* | Manager |
| VW_ACTIVE_EMPLOYEES | 13 | HIRE_DATE | *(absent)* | 2016-04-18 |
| VW_ACTIVE_EMPLOYEES | 13 | JOB_CODE | *(absent)* | MGR-DEV |
| VW_ACTIVE_EMPLOYEES | 13 | JOB_ID | *(absent)* | 30 |
| VW_ACTIVE_EMPLOYEES | 13 | JOB_TITLE | *(absent)* | Development Manager |
| VW_ACTIVE_EMPLOYEES | 13 | LAST_NAME | *(absent)* | GARCIA |
| VW_ACTIVE_EMPLOYEES | 13 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 13 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 13 | MANAGER_EMP_ID | *(absent)* | 30 |
| VW_ACTIVE_EMPLOYEES | 13 | MANAGER_NAME | *(absent)* | RACHEL THOMPSON |
| VW_ACTIVE_EMPLOYEES | 13 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 13 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 13 | PHONE_WORK | *(absent)* | 312-555-2102 |
| VW_ACTIVE_EMPLOYEES | 13 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 13 | TENURE_YEARS | *(absent)* | 8.1 |
| VW_ACTIVE_EMPLOYEES | 14 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 14 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 14 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 14 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 14 | CURRENT_SALARY | *(absent)* | 115000 |
| VW_ACTIVE_EMPLOYEES | 14 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 14 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 14 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 14 | EMAIL | *(absent)* | maria.rodriguez@company.com |
| VW_ACTIVE_EMPLOYEES | 14 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 14 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 14 | EMP_ID | *(absent)* | 32 |
| VW_ACTIVE_EMPLOYEES | 14 | EMP_NUMBER | *(absent)* | EMP-000032 |
| VW_ACTIVE_EMPLOYEES | 14 | FIRST_NAME | *(absent)* | MARIA |
| VW_ACTIVE_EMPLOYEES | 14 | FULL_NAME | *(absent)* | MARIA RODRIGUEZ |
| VW_ACTIVE_EMPLOYEES | 14 | GRADE_ID | *(absent)* | 4 |
| VW_ACTIVE_EMPLOYEES | 14 | GRADE_NAME | *(absent)* | Senior |
| VW_ACTIVE_EMPLOYEES | 14 | HIRE_DATE | *(absent)* | 2017-07-24 |
| VW_ACTIVE_EMPLOYEES | 14 | JOB_CODE | *(absent)* | SR-DEV |
| VW_ACTIVE_EMPLOYEES | 14 | JOB_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 14 | JOB_TITLE | *(absent)* | Senior Developer |
| VW_ACTIVE_EMPLOYEES | 14 | LAST_NAME | *(absent)* | RODRIGUEZ |
| VW_ACTIVE_EMPLOYEES | 14 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 14 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 14 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 14 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 14 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 14 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 14 | PHONE_WORK | *(absent)* | 312-555-2103 |
| VW_ACTIVE_EMPLOYEES | 14 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 14 | TENURE_YEARS | *(absent)* | 6.9 |
| VW_ACTIVE_EMPLOYEES | 15 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 15 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 15 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 15 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 15 | CURRENT_SALARY | *(absent)* | 110000 |
| VW_ACTIVE_EMPLOYEES | 15 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 15 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 15 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 15 | EMAIL | *(absent)* | daniel.lee@company.com |
| VW_ACTIVE_EMPLOYEES | 15 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 15 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 15 | EMP_ID | *(absent)* | 33 |
| VW_ACTIVE_EMPLOYEES | 15 | EMP_NUMBER | *(absent)* | EMP-000033 |
| VW_ACTIVE_EMPLOYEES | 15 | FIRST_NAME | *(absent)* | DANIEL |
| VW_ACTIVE_EMPLOYEES | 15 | FULL_NAME | *(absent)* | DANIEL LEE |
| VW_ACTIVE_EMPLOYEES | 15 | GRADE_ID | *(absent)* | 4 |
| VW_ACTIVE_EMPLOYEES | 15 | GRADE_NAME | *(absent)* | Senior |
| VW_ACTIVE_EMPLOYEES | 15 | HIRE_DATE | *(absent)* | 2018-02-12 |
| VW_ACTIVE_EMPLOYEES | 15 | JOB_CODE | *(absent)* | SR-DBA |
| VW_ACTIVE_EMPLOYEES | 15 | JOB_ID | *(absent)* | 41 |
| VW_ACTIVE_EMPLOYEES | 15 | JOB_TITLE | *(absent)* | Senior DBA |
| VW_ACTIVE_EMPLOYEES | 15 | LAST_NAME | *(absent)* | LEE |
| VW_ACTIVE_EMPLOYEES | 15 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 15 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 15 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 15 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 15 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 15 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 15 | PHONE_WORK | *(absent)* | 312-555-2104 |
| VW_ACTIVE_EMPLOYEES | 15 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 15 | TENURE_YEARS | *(absent)* | 6.3 |
| VW_ACTIVE_EMPLOYEES | 16 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 16 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 16 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 16 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 16 | CURRENT_SALARY | *(absent)* | 82000 |
| VW_ACTIVE_EMPLOYEES | 16 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 16 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 16 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 16 | EMAIL | *(absent)* | jessica.nguyen@company.com |
| VW_ACTIVE_EMPLOYEES | 16 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 16 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 16 | EMP_ID | *(absent)* | 34 |
| VW_ACTIVE_EMPLOYEES | 16 | EMP_NUMBER | *(absent)* | EMP-000034 |
| VW_ACTIVE_EMPLOYEES | 16 | FIRST_NAME | *(absent)* | JESSICA |
| VW_ACTIVE_EMPLOYEES | 16 | FULL_NAME | *(absent)* | JESSICA NGUYEN |
| VW_ACTIVE_EMPLOYEES | 16 | GRADE_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 16 | GRADE_NAME | *(absent)* | Mid-Level |
| VW_ACTIVE_EMPLOYEES | 16 | HIRE_DATE | *(absent)* | 2019-05-06 |
| VW_ACTIVE_EMPLOYEES | 16 | JOB_CODE | *(absent)* | DEV |
| VW_ACTIVE_EMPLOYEES | 16 | JOB_ID | *(absent)* | 50 |
| VW_ACTIVE_EMPLOYEES | 16 | JOB_TITLE | *(absent)* | Software Developer |
| VW_ACTIVE_EMPLOYEES | 16 | LAST_NAME | *(absent)* | NGUYEN |
| VW_ACTIVE_EMPLOYEES | 16 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 16 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 16 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 16 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 16 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 16 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 16 | PHONE_WORK | *(absent)* | 312-555-2105 |
| VW_ACTIVE_EMPLOYEES | 16 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 16 | TENURE_YEARS | *(absent)* | 5 |
| VW_ACTIVE_EMPLOYEES | 17 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 17 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 17 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 17 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 17 | CURRENT_SALARY | *(absent)* | 78000 |
| VW_ACTIVE_EMPLOYEES | 17 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 17 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 17 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 17 | EMAIL | *(absent)* | chris.anderson@company.com |
| VW_ACTIVE_EMPLOYEES | 17 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 17 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 17 | EMP_ID | *(absent)* | 35 |
| VW_ACTIVE_EMPLOYEES | 17 | EMP_NUMBER | *(absent)* | EMP-000035 |
| VW_ACTIVE_EMPLOYEES | 17 | FIRST_NAME | *(absent)* | CHRIS |
| VW_ACTIVE_EMPLOYEES | 17 | FULL_NAME | *(absent)* | CHRIS ANDERSON |
| VW_ACTIVE_EMPLOYEES | 17 | GRADE_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 17 | GRADE_NAME | *(absent)* | Mid-Level |
| VW_ACTIVE_EMPLOYEES | 17 | HIRE_DATE | *(absent)* | 2020-08-17 |
| VW_ACTIVE_EMPLOYEES | 17 | JOB_CODE | *(absent)* | DEV |
| VW_ACTIVE_EMPLOYEES | 17 | JOB_ID | *(absent)* | 50 |
| VW_ACTIVE_EMPLOYEES | 17 | JOB_TITLE | *(absent)* | Software Developer |
| VW_ACTIVE_EMPLOYEES | 17 | LAST_NAME | *(absent)* | ANDERSON |
| VW_ACTIVE_EMPLOYEES | 17 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 17 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 17 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 17 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 17 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 17 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 17 | PHONE_WORK | *(absent)* | 312-555-2106 |
| VW_ACTIVE_EMPLOYEES | 17 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 17 | TENURE_YEARS | *(absent)* | 3.8 |
| VW_ACTIVE_EMPLOYEES | 18 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 18 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 18 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 18 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 18 | CURRENT_SALARY | *(absent)* | 70000 |
| VW_ACTIVE_EMPLOYEES | 18 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 18 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 18 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 18 | EMAIL | *(absent)* | priya.sharma@company.com |
| VW_ACTIVE_EMPLOYEES | 18 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 18 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 18 | EMP_ID | *(absent)* | 36 |
| VW_ACTIVE_EMPLOYEES | 18 | EMP_NUMBER | *(absent)* | EMP-000036 |
| VW_ACTIVE_EMPLOYEES | 18 | FIRST_NAME | *(absent)* | PRIYA |
| VW_ACTIVE_EMPLOYEES | 18 | FULL_NAME | *(absent)* | PRIYA SHARMA |
| VW_ACTIVE_EMPLOYEES | 18 | GRADE_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 18 | GRADE_NAME | *(absent)* | Mid-Level |
| VW_ACTIVE_EMPLOYEES | 18 | HIRE_DATE | *(absent)* | 2021-03-22 |
| VW_ACTIVE_EMPLOYEES | 18 | JOB_CODE | *(absent)* | QA |
| VW_ACTIVE_EMPLOYEES | 18 | JOB_ID | *(absent)* | 51 |
| VW_ACTIVE_EMPLOYEES | 18 | JOB_TITLE | *(absent)* | QA Analyst |
| VW_ACTIVE_EMPLOYEES | 18 | LAST_NAME | *(absent)* | SHARMA |
| VW_ACTIVE_EMPLOYEES | 18 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 18 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 18 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 18 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 18 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 18 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 18 | PHONE_WORK | *(absent)* | 312-555-2107 |
| VW_ACTIVE_EMPLOYEES | 18 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 18 | TENURE_YEARS | *(absent)* | 3.2 |
| VW_ACTIVE_EMPLOYEES | 19 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 19 | COST_CENTER | *(absent)* | CC-1310 |
| VW_ACTIVE_EMPLOYEES | 19 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 19 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 19 | CURRENT_SALARY | *(absent)* | 55000 |
| VW_ACTIVE_EMPLOYEES | 19 | DEPT_CODE | *(absent)* | ITDEV |
| VW_ACTIVE_EMPLOYEES | 19 | DEPT_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 19 | DEPT_NAME | *(absent)* | IT - Development |
| VW_ACTIVE_EMPLOYEES | 19 | EMAIL | *(absent)* | alex.taylor@company.com |
| VW_ACTIVE_EMPLOYEES | 19 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 19 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 19 | EMP_ID | *(absent)* | 37 |
| VW_ACTIVE_EMPLOYEES | 19 | EMP_NUMBER | *(absent)* | EMP-000037 |
| VW_ACTIVE_EMPLOYEES | 19 | FIRST_NAME | *(absent)* | ALEX |
| VW_ACTIVE_EMPLOYEES | 19 | FULL_NAME | *(absent)* | ALEX TAYLOR |
| VW_ACTIVE_EMPLOYEES | 19 | GRADE_ID | *(absent)* | 2 |
| VW_ACTIVE_EMPLOYEES | 19 | GRADE_NAME | *(absent)* | Junior |
| VW_ACTIVE_EMPLOYEES | 19 | HIRE_DATE | *(absent)* | 2022-01-10 |
| VW_ACTIVE_EMPLOYEES | 19 | JOB_CODE | *(absent)* | JR-DEV |
| VW_ACTIVE_EMPLOYEES | 19 | JOB_ID | *(absent)* | 60 |
| VW_ACTIVE_EMPLOYEES | 19 | JOB_TITLE | *(absent)* | Junior Developer |
| VW_ACTIVE_EMPLOYEES | 19 | LAST_NAME | *(absent)* | TAYLOR |
| VW_ACTIVE_EMPLOYEES | 19 | LOCATION_CODE | *(absent)* | CHI |
| VW_ACTIVE_EMPLOYEES | 19 | LOCATION_NAME | *(absent)* | Chicago Regional Office |
| VW_ACTIVE_EMPLOYEES | 19 | MANAGER_EMP_ID | *(absent)* | 31 |
| VW_ACTIVE_EMPLOYEES | 19 | MANAGER_NAME | *(absent)* | KEVIN GARCIA |
| VW_ACTIVE_EMPLOYEES | 19 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 19 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 19 | PHONE_WORK | *(absent)* | 312-555-2108 |
| VW_ACTIVE_EMPLOYEES | 19 | STATE_PROVINCE | *(absent)* | IL |
| VW_ACTIVE_EMPLOYEES | 19 | TENURE_YEARS | *(absent)* | 2.4 |
| VW_ACTIVE_EMPLOYEES | 2 | CITY | *(absent)* | New York |
| VW_ACTIVE_EMPLOYEES | 2 | COST_CENTER | *(absent)* | CC-1200 |
| VW_ACTIVE_EMPLOYEES | 2 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 2 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 2 | CURRENT_SALARY | *(absent)* | 380000 |
| VW_ACTIVE_EMPLOYEES | 2 | DEPT_CODE | *(absent)* | FIN |
| VW_ACTIVE_EMPLOYEES | 2 | DEPT_ID | *(absent)* | 20 |
| VW_ACTIVE_EMPLOYEES | 2 | DEPT_NAME | *(absent)* | Finance & Accounting |
| VW_ACTIVE_EMPLOYEES | 2 | EMAIL | *(absent)* | sarah.chen@company.com |
| VW_ACTIVE_EMPLOYEES | 2 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 2 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 2 | EMP_ID | *(absent)* | 2 |
| VW_ACTIVE_EMPLOYEES | 2 | EMP_NUMBER | *(absent)* | EMP-000002 |
| VW_ACTIVE_EMPLOYEES | 2 | FIRST_NAME | *(absent)* | SARAH |
| VW_ACTIVE_EMPLOYEES | 2 | FULL_NAME | *(absent)* | SARAH CHEN |
| VW_ACTIVE_EMPLOYEES | 2 | GRADE_ID | *(absent)* | 10 |
| VW_ACTIVE_EMPLOYEES | 2 | GRADE_NAME | *(absent)* | C-Suite |
| VW_ACTIVE_EMPLOYEES | 2 | HIRE_DATE | *(absent)* | 2012-06-01 |
| VW_ACTIVE_EMPLOYEES | 2 | JOB_CODE | *(absent)* | CFO |
| VW_ACTIVE_EMPLOYEES | 2 | JOB_ID | *(absent)* | 2 |
| VW_ACTIVE_EMPLOYEES | 2 | JOB_TITLE | *(absent)* | Chief Financial Officer |
| VW_ACTIVE_EMPLOYEES | 2 | LAST_NAME | *(absent)* | CHEN |
| VW_ACTIVE_EMPLOYEES | 2 | LOCATION_CODE | *(absent)* | HQ |
| VW_ACTIVE_EMPLOYEES | 2 | LOCATION_NAME | *(absent)* | Corporate Headquarters |
| VW_ACTIVE_EMPLOYEES | 2 | MANAGER_EMP_ID | *(absent)* | 1 |
| VW_ACTIVE_EMPLOYEES | 2 | MANAGER_NAME | *(absent)* | JAMES RICHARDSON |
| VW_ACTIVE_EMPLOYEES | 2 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 2 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 2 | PHONE_WORK | *(absent)* | 212-555-1002 |
| VW_ACTIVE_EMPLOYEES | 2 | STATE_PROVINCE | *(absent)* | NY |
| VW_ACTIVE_EMPLOYEES | 2 | TENURE_YEARS | *(absent)* | 12 |
| VW_ACTIVE_EMPLOYEES | 20 | CITY | *(absent)* | San Francisco |
| VW_ACTIVE_EMPLOYEES | 20 | COST_CENTER | *(absent)* | CC-1400 |
| VW_ACTIVE_EMPLOYEES | 20 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 20 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 20 | CURRENT_SALARY | *(absent)* | 280000 |
| VW_ACTIVE_EMPLOYEES | 20 | DEPT_CODE | *(absent)* | SALES |
| VW_ACTIVE_EMPLOYEES | 20 | DEPT_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 20 | DEPT_NAME | *(absent)* | Sales |
| VW_ACTIVE_EMPLOYEES | 20 | EMAIL | *(absent)* | mark.davis@company.com |
| VW_ACTIVE_EMPLOYEES | 20 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 20 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 20 | EMP_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 20 | EMP_NUMBER | *(absent)* | EMP-000040 |
| VW_ACTIVE_EMPLOYEES | 20 | FIRST_NAME | *(absent)* | MARK |
| VW_ACTIVE_EMPLOYEES | 20 | FULL_NAME | *(absent)* | MARK DAVIS |
| VW_ACTIVE_EMPLOYEES | 20 | GRADE_ID | *(absent)* | 9 |
| VW_ACTIVE_EMPLOYEES | 20 | GRADE_NAME | *(absent)* | VP |
| VW_ACTIVE_EMPLOYEES | 20 | HIRE_DATE | *(absent)* | 2014-11-03 |
| VW_ACTIVE_EMPLOYEES | 20 | JOB_CODE | *(absent)* | VP-SALES |
| VW_ACTIVE_EMPLOYEES | 20 | JOB_ID | *(absent)* | 12 |
| VW_ACTIVE_EMPLOYEES | 20 | JOB_TITLE | *(absent)* | VP of Sales |
| VW_ACTIVE_EMPLOYEES | 20 | LAST_NAME | *(absent)* | DAVIS |
| VW_ACTIVE_EMPLOYEES | 20 | LOCATION_CODE | *(absent)* | SF |
| VW_ACTIVE_EMPLOYEES | 20 | LOCATION_NAME | *(absent)* | San Francisco Branch |
| VW_ACTIVE_EMPLOYEES | 20 | MANAGER_EMP_ID | *(absent)* | 1 |
| VW_ACTIVE_EMPLOYEES | 20 | MANAGER_NAME | *(absent)* | JAMES RICHARDSON |
| VW_ACTIVE_EMPLOYEES | 20 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 20 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 20 | PHONE_WORK | *(absent)* | 415-555-3101 |
| VW_ACTIVE_EMPLOYEES | 20 | STATE_PROVINCE | *(absent)* | CA |
| VW_ACTIVE_EMPLOYEES | 20 | TENURE_YEARS | *(absent)* | 9.5 |
| VW_ACTIVE_EMPLOYEES | 21 | CITY | *(absent)* | San Francisco |
| VW_ACTIVE_EMPLOYEES | 21 | COST_CENTER | *(absent)* | CC-1400 |
| VW_ACTIVE_EMPLOYEES | 21 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 21 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 21 | CURRENT_SALARY | *(absent)* | 130000 |
| VW_ACTIVE_EMPLOYEES | 21 | DEPT_CODE | *(absent)* | SALES |
| VW_ACTIVE_EMPLOYEES | 21 | DEPT_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 21 | DEPT_NAME | *(absent)* | Sales |
| VW_ACTIVE_EMPLOYEES | 21 | EMAIL | *(absent)* | ashley.brown@company.com |
| VW_ACTIVE_EMPLOYEES | 21 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 21 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 21 | EMP_ID | *(absent)* | 41 |
| VW_ACTIVE_EMPLOYEES | 21 | EMP_NUMBER | *(absent)* | EMP-000041 |
| VW_ACTIVE_EMPLOYEES | 21 | FIRST_NAME | *(absent)* | ASHLEY |
| VW_ACTIVE_EMPLOYEES | 21 | FULL_NAME | *(absent)* | ASHLEY BROWN |
| VW_ACTIVE_EMPLOYEES | 21 | GRADE_ID | *(absent)* | 6 |
| VW_ACTIVE_EMPLOYEES | 21 | GRADE_NAME | *(absent)* | Manager |
| VW_ACTIVE_EMPLOYEES | 21 | HIRE_DATE | *(absent)* | 2017-06-19 |
| VW_ACTIVE_EMPLOYEES | 21 | JOB_CODE | *(absent)* | MGR-SALES |
| VW_ACTIVE_EMPLOYEES | 21 | JOB_ID | *(absent)* | 33 |
| VW_ACTIVE_EMPLOYEES | 21 | JOB_TITLE | *(absent)* | Sales Manager |
| VW_ACTIVE_EMPLOYEES | 21 | LAST_NAME | *(absent)* | BROWN |
| VW_ACTIVE_EMPLOYEES | 21 | LOCATION_CODE | *(absent)* | SF |
| VW_ACTIVE_EMPLOYEES | 21 | LOCATION_NAME | *(absent)* | San Francisco Branch |
| VW_ACTIVE_EMPLOYEES | 21 | MANAGER_EMP_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 21 | MANAGER_NAME | *(absent)* | MARK DAVIS |
| VW_ACTIVE_EMPLOYEES | 21 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 21 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 21 | PHONE_WORK | *(absent)* | 415-555-3102 |
| VW_ACTIVE_EMPLOYEES | 21 | STATE_PROVINCE | *(absent)* | CA |
| VW_ACTIVE_EMPLOYEES | 21 | TENURE_YEARS | *(absent)* | 7 |
| VW_ACTIVE_EMPLOYEES | 22 | CITY | *(absent)* | San Francisco |
| VW_ACTIVE_EMPLOYEES | 22 | COST_CENTER | *(absent)* | CC-1400 |
| VW_ACTIVE_EMPLOYEES | 22 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 22 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 22 | CURRENT_SALARY | *(absent)* | 105000 |
| VW_ACTIVE_EMPLOYEES | 22 | DEPT_CODE | *(absent)* | SALES |
| VW_ACTIVE_EMPLOYEES | 22 | DEPT_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 22 | DEPT_NAME | *(absent)* | Sales |
| VW_ACTIVE_EMPLOYEES | 22 | EMAIL | *(absent)* | jason.wilson@company.com |
| VW_ACTIVE_EMPLOYEES | 22 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 22 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 22 | EMP_ID | *(absent)* | 42 |
| VW_ACTIVE_EMPLOYEES | 22 | EMP_NUMBER | *(absent)* | EMP-000042 |
| VW_ACTIVE_EMPLOYEES | 22 | FIRST_NAME | *(absent)* | JASON |
| VW_ACTIVE_EMPLOYEES | 22 | FULL_NAME | *(absent)* | JASON WILSON |
| VW_ACTIVE_EMPLOYEES | 22 | GRADE_ID | *(absent)* | 4 |
| VW_ACTIVE_EMPLOYEES | 22 | GRADE_NAME | *(absent)* | Senior |
| VW_ACTIVE_EMPLOYEES | 22 | HIRE_DATE | *(absent)* | 2019-09-16 |
| VW_ACTIVE_EMPLOYEES | 22 | JOB_CODE | *(absent)* | SR-SALES |
| VW_ACTIVE_EMPLOYEES | 22 | JOB_ID | *(absent)* | 43 |
| VW_ACTIVE_EMPLOYEES | 22 | JOB_TITLE | *(absent)* | Senior Sales Rep |
| VW_ACTIVE_EMPLOYEES | 22 | LAST_NAME | *(absent)* | WILSON |
| VW_ACTIVE_EMPLOYEES | 22 | LOCATION_CODE | *(absent)* | SF |
| VW_ACTIVE_EMPLOYEES | 22 | LOCATION_NAME | *(absent)* | San Francisco Branch |
| VW_ACTIVE_EMPLOYEES | 22 | MANAGER_EMP_ID | *(absent)* | 41 |
| VW_ACTIVE_EMPLOYEES | 22 | MANAGER_NAME | *(absent)* | ASHLEY BROWN |
| VW_ACTIVE_EMPLOYEES | 22 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 22 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 22 | PHONE_WORK | *(absent)* | 415-555-3103 |
| VW_ACTIVE_EMPLOYEES | 22 | STATE_PROVINCE | *(absent)* | CA |
| VW_ACTIVE_EMPLOYEES | 22 | TENURE_YEARS | *(absent)* | 4.7 |
| VW_ACTIVE_EMPLOYEES | 23 | CITY | *(absent)* | San Francisco |
| VW_ACTIVE_EMPLOYEES | 23 | COST_CENTER | *(absent)* | CC-1400 |
| VW_ACTIVE_EMPLOYEES | 23 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 23 | CURRENCY_CODE | *(absent)* | USD |
| VW_ACTIVE_EMPLOYEES | 23 | CURRENT_SALARY | *(absent)* | 65000 |
| VW_ACTIVE_EMPLOYEES | 23 | DEPT_CODE | *(absent)* | SALES |
| VW_ACTIVE_EMPLOYEES | 23 | DEPT_ID | *(absent)* | 40 |
| VW_ACTIVE_EMPLOYEES | 23 | DEPT_NAME | *(absent)* | Sales |
| VW_ACTIVE_EMPLOYEES | 23 | EMAIL | *(absent)* | samantha.moore@company.com |
| VW_ACTIVE_EMPLOYEES | 23 | EMPLOYMENT_STATUS | *(absent)* | ACTIVE |
| VW_ACTIVE_EMPLOYEES | 23 | EMPLOYMENT_TYPE | *(absent)* | FULL_TIME |
| VW_ACTIVE_EMPLOYEES | 23 | EMP_ID | *(absent)* | 43 |
| VW_ACTIVE_EMPLOYEES | 23 | EMP_NUMBER | *(absent)* | EMP-000043 |
| VW_ACTIVE_EMPLOYEES | 23 | FIRST_NAME | *(absent)* | SAMANTHA |
| VW_ACTIVE_EMPLOYEES | 23 | FULL_NAME | *(absent)* | SAMANTHA MOORE |
| VW_ACTIVE_EMPLOYEES | 23 | GRADE_ID | *(absent)* | 3 |
| VW_ACTIVE_EMPLOYEES | 23 | GRADE_NAME | *(absent)* | Mid-Level |
| VW_ACTIVE_EMPLOYEES | 23 | HIRE_DATE | *(absent)* | 2021-02-08 |
| VW_ACTIVE_EMPLOYEES | 23 | JOB_CODE | *(absent)* | SALES-REP |
| VW_ACTIVE_EMPLOYEES | 23 | JOB_ID | *(absent)* | 54 |
| VW_ACTIVE_EMPLOYEES | 23 | JOB_TITLE | *(absent)* | Sales Representative |
| VW_ACTIVE_EMPLOYEES | 23 | LAST_NAME | *(absent)* | MOORE |
| VW_ACTIVE_EMPLOYEES | 23 | LOCATION_CODE | *(absent)* | SF |
| VW_ACTIVE_EMPLOYEES | 23 | LOCATION_NAME | *(absent)* | San Francisco Branch |
| VW_ACTIVE_EMPLOYEES | 23 | MANAGER_EMP_ID | *(absent)* | 41 |
| VW_ACTIVE_EMPLOYEES | 23 | MANAGER_NAME | *(absent)* | ASHLEY BROWN |
| VW_ACTIVE_EMPLOYEES | 23 | PAY_FREQUENCY | *(absent)* | MONTHLY |
| VW_ACTIVE_EMPLOYEES | 23 | PHONE_MOBILE | *(absent)* | \N |
| VW_ACTIVE_EMPLOYEES | 23 | PHONE_WORK | *(absent)* | 415-555-3104 |
| VW_ACTIVE_EMPLOYEES | 23 | STATE_PROVINCE | *(absent)* | CA |
| VW_ACTIVE_EMPLOYEES | 23 | TENURE_YEARS | *(absent)* | 3.3 |
| VW_ACTIVE_EMPLOYEES | 3 | CITY | *(absent)* | Chicago |
| VW_ACTIVE_EMPLOYEES | 3 | COST_CENTER | *(absent)* | CC-1300 |
| VW_ACTIVE_EMPLOYEES | 3 | COUNTRY_CODE | *(absent)* | US |
| VW_ACTIVE_EMPLOYEES | 3 | CURRENCY_CODE | *(absent)* | USD |
