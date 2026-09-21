# Parallel run – Level 2

Result: **FAIL** (8/10 scenarios)

| scenario | expected (contract) | legacy | target | verdict |
|---|---|---|---|---|
| auth.login.ok | ok {emp_id=2} | n/a | ok {emp_id=2} | PASS |
| auth.login.unknown-user | `-20301` | n/a | `-20301` | PASS |
| auth.login.wrong-password | `-20301` | n/a | `-20301` | PASS |
| auth.refresh.rotates | ok {tokenType=Bearer} | n/a | ok {tokenType=Bearer} | PASS |
| auth.password.too-short | `-20310` | n/a | `-20310` | PASS |
| auth.password.no-upper | `-20311` | n/a | `-20311` | PASS |
| auth.password.no-digit | `-20312` | n/a | `-20312` | PASS |
| auth.lockout.after-5-failures | `RATE_LIMITED` | n/a (documented divergence, expects `-20301`) | `RATE_LIMITED` | PASS |
| sso.exchange.legacy-module | ok {module=EMPLOYEE} | n/a | `VALIDATION_FAILED` | TARGET-DIFF |
| sso.exchange.new-module-rejected | `SSO_MODULE_NOT_LEGACY` | n/a | `VALIDATION_FAILED` | TARGET-DIFF |
