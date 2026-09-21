// njs handler for LEGACY module paths (proxy/module-legacy.conf; contracts/p0-foundation/openapi.yaml
// POST /legacy/sso/exchange). It issues the SSO exchange the contract requires - POST with a JSON
// body {module, clientIp}, module in the lower-case ProxyModule wire form - reads formsModule and
// otherparams from the JSON response and forwards to the Forms servlet through @forms.
// Non-200 ApiError responses (401 TOKEN_INVALID, 403 SSO_MODULE_NOT_LEGACY, 502
// SSO_LEGACY_UNAVAILABLE) are returned to the client unchanged.
//
// DECISION P0-D1 (golden-oracle mode OFF): no Oracle Forms runs in this phase, so the end-to-end
// exchange is untested-live; this file is covered by proxy/test_proxy_config.py only.

var WIRE = {
  AUTH: 'auth',
  EMPLOYEE: 'employee',
  PAYROLL: 'payroll',
  PAYROLL_ENGINE: 'payroll.engine',
  LEAVE: 'leave',
  PERFORMANCE: 'performance',
  REPORTING: 'reporting'
};

var EXCHANGE_URI = '/legacy/sso/exchange';

function exchangeBody(moduleFlag, clientIp) {
  var wire = WIRE[moduleFlag];
  if (!wire) {
    throw new Error('unknown proxy module ' + moduleFlag);
  }
  return JSON.stringify({ module: wire, clientIp: clientIp });
}

function launch(r) {
  var body = exchangeBody(r.variables.hrms_module, r.variables.remote_addr);
  r.subrequest(EXCHANGE_URI, { method: 'POST', body: body }, function (res) {
    if (res.status !== 200) {
      r.headersOut['Content-Type'] = 'application/json';
      r.return(res.status, res.responseText);
      return;
    }
    var exchange = JSON.parse(res.responseText);
    r.variables.forms_module = exchange.formsModule;
    r.variables.forms_otherparams = exchange.otherparams;
    r.internalRedirect('@forms');
  });
}

export default { launch, exchangeBody, EXCHANGE_URI };
