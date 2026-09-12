#!/usr/bin/env python3
"""G9c differential fuzzing of the serialization/normalization boundary.

Generates mutated bundles around the valid fixture, feeds the same case to all
three verifiers, and classifies:
  ACCEPT_SAME_ROOT   - all accept, identical derived claim root
  REJECT             - all reject (compatible issue classes not required)
  DISAGREEMENT       - verifiers disagree on status/claimability
  CRASH              - a verifier fails to produce a machine result

Every DISAGREEMENT or CRASH must be resolved (an implementation defect, a spec
ambiguity, or an explicitly documented implementation-specific difference).

Usage: python3 scripts/differential_fuzz.py
"""
import json
import os
import random
import shutil
import subprocess
import sys

BASE = json.load(open("etc/conformance/corpus/valid/trace-001.json"))
CASES_DIR = "/tmp/opencode/fuzz"
os.makedirs(CASES_DIR, exist_ok=True)
random.seed(20260805)


def canonical_json(v):
    if isinstance(v, dict):
        return "{" + ",".join(
            json.dumps(k) + ":" + canonical_json(v[k]) for k in sorted(v)
        ) + "}"
    if isinstance(v, list):
        return "[" + ",".join(canonical_json(x) for x in v) + "]"
    return json.dumps(v)


def write_case(case_id, obj, raw=None):
    if raw is not None:
        with open(f"{CASES_DIR}/{case_id}.json", "w") as f:
            f.write(raw)
    else:
        with open(f"{CASES_DIR}/{case_id}.json", "w") as f:
            f.write(json.dumps(obj, indent=2))
    return {"case_id": case_id}


cases = []


def add(case_id, obj=None, raw=None):
    cases.append(write_case(case_id, obj, raw))


def deepcopy(x):
    return json.loads(json.dumps(x))


def shuffle_keys(obj, depth=0):
    if isinstance(obj, dict):
        items = list(obj.items())
        random.shuffle(items)
        return {k: shuffle_keys(v, depth + 1) for k, v in items}
    if isinstance(obj, list):
        return [shuffle_keys(x, depth + 1) for x in obj]
    return obj


# 1. key-order invariance (must ACCEPT with the SAME root)
for i in range(6):
    add(f"keyorder-{i}", shuffle_keys(deepcopy(BASE)))

# 2. whitespace / formatting variants
raw = json.dumps(BASE, indent=2)
add("whitespace-compact", raw=json.dumps(BASE, separators=(",", ":")))
add("whitespace-extra", raw=raw.replace("\n", "\n\n").replace("  ", "    "))
add("whitespace-noindent", raw=json.dumps(BASE))

# 3. unicode escapes that decode to identical content (must ACCEPT same root)
esc = raw.replace('"claim/class": "attested"', '"claim/class": "\\u0061ttested"')
add("unicode-escape-claim", raw=esc)

# 4. extra informational fields (must ACCEPT same root, ignored)
b = deepcopy(BASE)
b["bundle/informational"] = {"note": "added field"}
add("extra-top-field", b)
b = deepcopy(BASE)
b["environment"]["environment/informational"]["source-revisions"] = {"v": "x"}
add("extra-informational", b)

# 5. empty collections (ACCEPT same root)
b = deepcopy(BASE)
b["validation-receipts"] = []
add("empty-collections", b)

# 6. integer representations of the profile version (REJECT: unknown profile)
b = deepcopy(BASE)
b["profile"]["profile/version"] = 1
add("int-version", b)
b = deepcopy(BASE)
b["profile"]["profile/version"] = 1.0
add("float-version", b)

# 7. malformed roots (REJECT)
b = deepcopy(BASE)
b["reconciliation"]["reconciliation/root"] = "not-a-sha"
add("malformed-root", b)
b = deepcopy(BASE)
b["claim"]["reconciliation/root"] = "sha256:short"
add("truncated-root", b)

# 8. wrong root, otherwise valid (REJECT reference; minimal may differ)
b = deepcopy(BASE)
b["reconciliation"]["reconciliation/root"] = "sha256:0" * 1 + "1" * 62
add("wrong-root", b)

# 9. inconsistent embedded envelopes (REJECT)
b = deepcopy(BASE)
b["plan"]["environment/root"] = "sha256:other"
add("plan-env-mismatch", b)
b = deepcopy(BASE)
b["coverage"]["coverage/complete?"] = False
add("coverage-incomplete", b)

# 10. duplicate ids across receipts (REJECT)
b = deepcopy(BASE)
b["execution-receipts"] = [{"step/id": "replay", "subject/id": "a", "subject/root": "sha256:a"}]
add("duplicate-receipt-id", b)

# 11. malformed claim status (REJECT)
b = deepcopy(BASE)
b["claim"]["claim/status"] = "maybe"
add("malformed-claim-status", b)

# 12. deeply nested informational value (ACCEPT, bounded depth)
b = deepcopy(BASE)
n = {"x": {}}
for _ in range(50):
    n = {"x": n}
b["environment"]["environment/informational"]["deep"] = n
add("deep-nesting", b)

# 13. number boundaries in a numeric field
b = deepcopy(BASE)
b["profile"]["profile/version"] = 2**31
add("int-boundary", b)
b = deepcopy(BASE)
b["profile"]["profile/version"] = 2.5
add("float-fraction", b)

# 14. missing claim entirely (REJECT: cannot be claimable)
b = deepcopy(BASE)
del b["claim"]
add("missing-claim", b)

# 15. extra unexpected top-level envelope (REJECT in clojure via bundle index?)
b = deepcopy(BASE)
b["plan"] = dict(BASE["plan"], **{"extra": True})
add("extra-plan-field", b)

# --- run the three verifiers ------------------------------------------------
manifest_path = f"{CASES_DIR}/manifest.json"
json.dump([{"case_id": c["case_id"]} for c in cases], open(manifest_path, "w"))

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    return r.returncode, r.stdout, r.stderr

clj_env = {**os.environ, "FUZZ_CASES_DIR": CASES_DIR, "FUZZ_MANIFEST": manifest_path}
clj = subprocess.run(["clojure", "-M:test:with-sew", "-i", "scripts/fuzz_batch.clj"],
                     capture_output=True, text=True, timeout=300, env=clj_env)
clj_results = {}
for line in (clj.stdout or "").splitlines():
    parts = line.split("|")
    if len(parts) >= 4:
        clj_results[parts[0]] = {"status": parts[1], "claimable": parts[2] == "true", "root": parts[3] or None}

def norm(s):
    return "pass" if s == "pass" else "reject"

def run_js(path):
    r = subprocess.run(["node", "scripts/verify3.mjs", "verify", path], capture_output=True, text=True, timeout=60)
    return json.loads(r.stdout) if r.returncode == 0 else None

def run_py(path):
    r = subprocess.run(["python3", "scripts/bundle_verify.py", path], capture_output=True, text=True, timeout=60)
    return json.loads(r.stdout) if r.returncode == 0 else None

stats = {"ACCEPT_SAME_ROOT": 0, "REJECT": 0, "DISAGREEMENT": 0, "CRASH": 0}
problems = []
for c in cases:
    path = f"{CASES_DIR}/{c['case_id']}.json"
    js = run_js(path)
    py = run_py(path)
    cl = clj_results.get(c["case_id"])
    if js is None or py is None or cl is None:
        stats["CRASH"] += 1
        problems.append((c["case_id"], "crash", js, py, cl))
        continue
    js_s, js_r, js_c = norm(js.get("status")), js.get("claimable"), js.get("derived_claim_root")
    py_s, py_r, py_c = norm(py["verification/status"]), py["claimable?"], py.get("derived-claim/root")
    cl_s, cl_r, cl_c = norm(cl["status"]), cl["claimable"], cl.get("root")
    statuses = {js_s, py_s, cl_s}
    if len(statuses) == 1 and js_s == "pass":
        roots = {js_c, py_c, cl_c}
        if len(roots) == 1:
            stats["ACCEPT_SAME_ROOT"] += 1
        else:
            stats["DISAGREEMENT"] += 1
            problems.append((c["case_id"], "root-mismatch", js_c, py_c, cl_c))
    elif len(statuses) == 1 and js_s == "reject":
        stats["REJECT"] += 1
    else:
        stats["DISAGREEMENT"] += 1
        problems.append((c["case_id"], "status-disagreement",
                         (js_s, js_r), (py_s, py_r), (cl_s, cl_r)))

print("DIFFERENTIAL FUZZ CLASSIFICATION")
for k, v in stats.items():
    print(f"  {k:<18} {v}")
for p in problems:
    print("  PROBLEM:", p)
print("VERDICT:", "PASS - no disagreements or crashes" if not problems else "FAIL")
sys.exit(0 if not problems else 1)
