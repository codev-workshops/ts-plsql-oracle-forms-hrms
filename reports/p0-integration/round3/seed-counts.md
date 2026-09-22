| table | oracle fixture inserts | pg fixture inserts | pg actual rows | status |
|---|---:|---:|---:|---|
| audit_log | - | 0 | 29 | OK (seed identical; extra rows written at runtime by the auth-service during L1/e2e/L2) |
| departments | 10 | 10 | 10 | OK |
| emergency_contacts | - | 0 | 0 | OK |
| employee_bank_accounts | - | 0 | 0 | OK |
| employee_dependents | - | 0 | 0 | OK |
| employee_history | - | 0 | 0 | OK |
| employee_pay_elements | - | 0 | 0 | OK |
| employee_tax_info | - | 0 | 0 | OK |
| employees | 24 | 24 | 24 | OK |
| error_log | - | 0 | 61 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| holidays | 10 | 10 | 10 | OK |
| job_grades | 10 | 10 | 10 | OK |
| job_titles | 26 | 26 | 26 | OK |
| leave_accrual_log | - | 0 | 0 | OK |
| leave_balances | 13 | 13 | 13 | OK |
| leave_requests | 5 | 5 | 5 | OK |
| leave_types | 6 | 6 | 6 | OK |
| locations | 3 | 3 | 3 | OK |
| lookup_values | - | 0 | 0 | OK |
| notification_queue | - | 0 | 0 | OK |
| pay_elements | 11 | 11 | 11 | OK |
| pay_periods | 2 | 2 | 2 | OK |
| payroll_details | 30 | 30 | 30 | OK |
| payroll_runs | 2 | 2 | 2 | OK |
| performance_goals | - | 0 | 0 | OK |
| performance_reviews | 4 | 4 | 4 | OK |
| refresh_tokens | - | 0 | 27 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| review_cycles | 1 | 1 | 1 | OK |
| revoked_jti | - | 0 | 8 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| role_permissions | - | 0 | 29 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| roles | - | 0 | 3 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| salary_records | 23 | 23 | 23 | OK |
| system_parameters | 10 | 10 | 10 | OK |
| tax_brackets | - | 0 | 0 | OK |
| user_accounts | - | 0 | 5 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| user_roles | - | 0 | 5 | P0-additive (Flyway V2 / runtime; no legacy counterpart) |
| user_sessions | - | 0 | 20 | OK (seed identical; extra rows written at runtime by the auth-service during L1/e2e/L2) |

Legacy tables (schema/tables) compared: 30 - present on PG: 30
Method: Oracle side = INSERT count in tools/fixtures/oracle/*.sql (no Oracle instance, golden-oracle mode OFF); PG side = live count(*) after applying tools/fixtures/pg/*.sql.
Oracle fixture tables absent on PG: []
RESULT: PASS
