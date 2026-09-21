"""Level-1 checks for the rendered proxy configuration and the SSO bridge handler.

Run: python3 proxy/test_proxy_config.py  (needs node for the sso_bridge.js checks; nginx is not
required - the Forms leg is untested-live per DECISION P0-D1).
"""

import json
import os
import shutil
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import render  # noqa: E402

LEGACY_FLAGS = {
    "AUTH": "NEW",
    "EMPLOYEE": "LEGACY",
    "PAYROLL": "LEGACY",
    "PAYROLL_ENGINE": "LEGACY",
    "LEAVE": "LEGACY",
    "PERFORMANCE": "LEGACY",
    "REPORTING": "LEGACY",
}

NODE_HARNESS = r"""
const fs = require('fs');
const vm = require('vm');
let src = fs.readFileSync(process.argv[1], 'utf8');
src = src.replace(/export default (\{[^}]*\});?/, 'globalThis.sso = $1;');
vm.runInThisContext(src);
const calls = [];
const r = {
  variables: { hrms_module: process.argv[2], remote_addr: '10.0.0.7' },
  headersOut: {},
  subrequest(uri, opts, cb) {
    calls.push({ uri, method: opts.method, body: JSON.parse(opts.body) });
    cb({ status: Number(process.argv[3]), responseText: process.argv[4] });
  },
  return(status, body) { calls.push({ returned: status, body }); },
  internalRedirect(name) { calls.push({ redirect: name, vars: Object.assign({}, r.variables) }); },
};
sso.launch(r);
process.stdout.write(JSON.stringify(calls));
"""


def read(name):
    with open(os.path.join(HERE, name), encoding="utf-8") as f:
        return f.read()


def run_bridge(module, status, response):
    node = shutil.which("node")
    if node is None:
        raise unittest.SkipTest("node not installed")
    out = subprocess.run(
        [node, "-e", NODE_HARNESS, os.path.join(HERE, "sso_bridge.js"), module, str(status), response],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return json.loads(out)


class LegacyModuleConfTest(unittest.TestCase):
    def test_legacy_conf_no_longer_relies_on_auth_request_or_launch_url_header(self):
        conf = read("module-legacy.conf")
        self.assertNotIn("auth_request", conf.replace("#", ""))
        self.assertNotIn("x_hrms_launch_url", conf.lower())
        self.assertNotIn("X-HRMS-Launch-Url", conf)
        self.assertIn("js_content sso.launch;", conf)

    def test_rendered_config_wires_the_bridge_and_forms_launch(self):
        rendered = render.render(LEGACY_FLAGS, read("nginx.conf.template"))
        self.assertIn("js_import sso from /etc/nginx/sso_bridge.js;", rendered)
        self.assertIn("js_var $forms_module;", rendered)
        self.assertIn("js_var $forms_otherparams;", rendered)
        self.assertIn("location = /legacy/sso/exchange {", rendered)
        self.assertIn("internal;", rendered)
        self.assertIn("proxy_set_header Content-Type application/json;", rendered)
        self.assertIn(
            "proxy_pass http://forms/forms/frmservlet?form=$forms_module&otherparams=$forms_otherparams;",
            rendered,
        )
        self.assertIn("include /etc/nginx/module-legacy.conf; set $hrms_module EMPLOYEE;", rendered)
        self.assertNotIn("X-HRMS-Launch-Url", rendered)


class SsoBridgeHandlerTest(unittest.TestCase):
    def test_exchange_is_a_post_with_lower_case_module_and_client_ip(self):
        response = json.dumps(
            {
                "formsModule": "HRMS_EMPLOYEE",
                "otherparams": "session_id=42&current_user=sarah.chen%40company.com&current_emp_id=2",
                "legacySessionId": 42,
                "expiresAt": "2024-06-30T12:00:00Z",
            }
        )
        calls = run_bridge("EMPLOYEE", 200, response)
        self.assertEqual(calls[0]["uri"], "/legacy/sso/exchange")
        self.assertEqual(calls[0]["method"], "POST")
        self.assertEqual(calls[0]["body"], {"module": "employee", "clientIp": "10.0.0.7"})
        self.assertEqual(calls[1]["redirect"], "@forms")
        self.assertEqual(calls[1]["vars"]["forms_module"], "HRMS_EMPLOYEE")
        self.assertEqual(
            calls[1]["vars"]["forms_otherparams"],
            "session_id=42&current_user=sarah.chen%40company.com&current_emp_id=2",
        )

    def test_payroll_engine_uses_dotted_wire_value(self):
        calls = run_bridge("PAYROLL_ENGINE", 200, json.dumps({"formsModule": "HRMS_PAYROLL", "otherparams": ""}))
        self.assertEqual(calls[0]["body"]["module"], "payroll.engine")

    def test_api_error_is_passed_through_unchanged(self):
        error = json.dumps({"code": "SSO_MODULE_NOT_LEGACY", "message": "not legacy", "traceId": "t-1"})
        calls = run_bridge("AUTH", 403, error)
        self.assertEqual(calls[0]["body"]["module"], "auth")
        self.assertEqual(calls[1], {"returned": 403, "body": error})


if __name__ == "__main__":
    unittest.main()
