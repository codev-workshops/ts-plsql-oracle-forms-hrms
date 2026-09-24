# proxy – reverse proxy with per-module flags

`flags.env` holds the frozen flag vocabulary (contracts/p0-foundation/README.md). `render.py`
validates the values (unknown value ⇒ exit 3, **the proxy refuses to start**) and renders
`nginx.conf` from `nginx.conf.template`:

```bash
HRMS_FLAG_EMPLOYEE=NEW_READONLY python3 proxy/render.py   # env overrides flags.env
nginx -t -c "$PWD/proxy/nginx.conf"
```

Routing rules implemented:

* `auth=NEW` only; `/api/auth/**`, `/login` → Spring/React; the Forms `HRMS_LOGIN` route returns 410.
* `GET /api/reference/**` always → Spring Boot.
* `LEGACY` module paths → `sso_bridge.js` (njs) issues `POST /legacy/sso/exchange` with the JSON body
  `{module: <lower-case wire value>, clientIp}` through the internal-only location (so the bridge is
  reachable from the proxy and never from clients), reads `formsModule`/`otherparams` from the JSON
  response and forwards to the Forms servlet (`@forms`); `NEW*` paths → Spring/React with the bearer
  token untouched. DECISION P0-D1: no Oracle Forms runs in this phase, so this leg is
  `untested-live`; `python3 proxy/test_proxy_config.py` covers the rendered configuration and the
  request body the bridge builds.
* `employee=NEW_READONLY`: GET/HEAD → new tier, other methods → Forms.
* `payroll=NEW` is refused unless `payroll.engine=JAVA` (flipped together, CUTOVER_PLAN.md §7).

The same flags are read by the auth-service (`hrms.legacy.proxy.modules`) so the SSO bridge rejects
`module=<NEW module>` with `SSO_MODULE_NOT_LEGACY`; keep `flags.env` and the service environment
in sync (both read `HRMS_FLAG_*`).
