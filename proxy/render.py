#!/usr/bin/env python3
"""Render proxy/nginx.conf.template from flags.env, refusing to start on an unknown value.

Usage: render.py [flags.env] [out.conf]
Exit 0 = rendered; 3 = invalid flag (proxy must not start).
"""
import os
import sys

ALLOWED = {
    "AUTH": {"NEW"},
    "EMPLOYEE": {"LEGACY", "NEW_READONLY", "NEW"},
    "PAYROLL": {"LEGACY", "NEW"},
    "PAYROLL_ENGINE": {"LEGACY", "JAVA"},
    "LEAVE": {"LEGACY", "NEW"},
    "PERFORMANCE": {"LEGACY", "NEW"},
    "REPORTING": {"LEGACY", "NEW"},
}

# Which upstream serves the module's UI and API paths for each flag value.
# LEGACY  -> forms (after /legacy/sso/exchange)          NEW -> spring / react
# NEW_READONLY -> GET to spring/react, writes to forms.
HERE = os.path.dirname(os.path.abspath(__file__))


def load(path):
    flags = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            k, _, v = line.partition("=")
            k = k.strip().removeprefix("HRMS_FLAG_")
            flags[k] = os.environ.get("HRMS_FLAG_" + k, v.strip())
    return flags


def validate(flags):
    errors = []
    for name, allowed in ALLOWED.items():
        v = flags.get(name)
        if v not in allowed:
            errors.append(f"flag {name.lower().replace('_', '.')}={v!r} not in {sorted(allowed)}")
    if flags.get("PAYROLL") == "NEW" and flags.get("PAYROLL_ENGINE") != "JAVA":
        errors.append("payroll=NEW requires payroll.engine=JAVA (flipped together, CUTOVER_PLAN §7)")
    return errors


def upstream(value, method_readonly=False):
    if value == "LEGACY":
        return "legacy"
    if value == "NEW_READONLY":
        return "new" if method_readonly else "legacy"
    return "new"


def render(flags, template):
    subs = {}
    for name in ALLOWED:
        v = flags[name]
        subs[f"{{{{{name}}}}}"] = v
        subs[f"{{{{{name}_UPSTREAM}}}}"] = upstream(v)
        subs[f"{{{{{name}_READ_UPSTREAM}}}}"] = upstream(v, method_readonly=True)
    out = template
    for k, v in subs.items():
        out = out.replace(k, v)
    if "{{" in out:
        raise SystemExit("unresolved placeholder in template: " + out[out.index("{{"):][:40])
    return out


def main():
    flags_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "flags.env")
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "nginx.conf")
    flags = load(flags_path)
    errors = validate(flags)
    if errors:
        for e in errors:
            print("REFUSING TO START:", e, file=sys.stderr)
        sys.exit(3)
    with open(os.path.join(HERE, "nginx.conf.template"), encoding="utf-8") as f:
        conf = render(flags, f.read())
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(conf)
    print("rendered", out_path, {k.lower().replace("_", "."): v for k, v in flags.items()})


if __name__ == "__main__":
    main()
