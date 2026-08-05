#!/usr/bin/env python3
"""
bundle_verify — read-only, second-implementation conformance bundle verifier.

Independently verifies a conformance bundle JSON WITHOUT sharing the Clojure
core code paths.  Consumes only the bundle, committed schemas, and the
canonical-JSON root convention.  Never regenerates or repairs; never resolves
missing evidence by running domain code.

Output (deterministic machine classification, cross-language parity):
  {:verification/status :pass|:rejected|:unsupported-version
   :outcome/class ...
   :claimable? bool
   :derived-claim/root <canonical-json sha256>
   :issue-codes [...]}

Diagnostic wording may differ from Clojure; machine classifications agree.

Usage:
  python3 scripts/bundle_verify.py <bundle.json>
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

BUNDLE_SCHEMA_VERSION = "conformance.bundle/v1"

# Mode -> permitted claim classes (mirrors resolver-sim.conformance.claim).
PERMITTED = {
    "attested": {"attested"},
    "reproduce": {"reproduced"},
    "candidate": {"candidate-compatible", "accepted-divergence", "not-evaluated"},
    "compare": {"candidate-compatible", "accepted-divergence", "not-evaluated"},
}

ISSUE_ENVELOPE_CLASS = {
    "unsupported-bundle-version": "version",
    "reconciliation-not-reproducible": "reconciliation",
    "derived-claim-mismatch": "claim",
    "claim-json-root-mismatch": "claim",
}


def canonical_json_str(x: Any) -> str:
    """Deterministic canonical JSON (recursively sorted keys) shared with the
    Clojure bundle verifier."""

    def sort_key(o):
        if isinstance(o, dict):
            return {str(k): sort_key(v) for k, v in sorted(o.items(), key=lambda kv: str(kv[0]))}
        if isinstance(o, list):
            return [sort_key(v) for v in o]
        return o

    return json.dumps(sort_key(x), separators=(",", ":"), sort_keys=True)


def sha256_hex(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def derive_claim(bundle: dict) -> dict[str, Any] | None:
    recon = bundle.get("reconciliation") or {}
    coverage = bundle.get("coverage") or {}
    plan = bundle.get("plan") or {}
    mode = plan.get("claim/mode") or "attested"
    ok = recon.get("reconciliation/status") == "pass" and bool(coverage.get("coverage/complete?"))
    if not ok:
        return None
    claim_class = {"attested": "attested", "reproduce": "reproduced"}.get(mode, "candidate-compatible")
    if mode in ("candidate", "compare") and claim_class not in PERMITTED[mode]:
        claim_class = "not-evaluated"
    if claim_class not in PERMITTED.get(mode, set()):
        return None
    claim = {
        "evaluation/mode": mode,
        "claim/class": claim_class,
        "claim/status": "pass",
        "reconciliation/root": recon.get("reconciliation/root"),
        "environment/root": recon.get("environment/root") or coverage.get("environment/root"),
    }
    claim["claim/json-root"] = sha256_hex(canonical_json_str(claim).encode("utf-8"))
    return claim


def verify_bundle(bundle: dict) -> dict[str, Any]:
    issues: list[dict[str, Any]] = []
    version = bundle.get("bundle/schema-version")
    if version != BUNDLE_SCHEMA_VERSION:
        issues.append({"issue/code": "unsupported-bundle-version",
                       "issue/details": {"schema-version": version}})

    if not issues:
        recon = bundle.get("reconciliation") or {}
        coverage = bundle.get("coverage") or {}
        plan = bundle.get("plan") or {}
        # reconciliation not reproducible: the supplied root must be present and
        # consistent (Python-side structural check)
        if not recon.get("reconciliation/root"):
            issues.append({"issue/code": "reconciliation-not-reproducible",
                           "issue/details": {}})
        # environment-root agreement across verdict envelopes
        plan_env = plan.get("environment/root")
        recon_env = recon.get("environment/root")
        cov_env = coverage.get("environment/root")
        if plan_env and recon_env and plan_env != recon_env:
            issues.append({"issue/code": "environment-root-disagreement",
                           "issue/details": {"plan": plan_env, "reconciliation": recon_env}})
        if recon_env and cov_env and recon_env != cov_env:
            issues.append({"issue/code": "environment-root-disagreement",
                           "issue/details": {"reconciliation": recon_env, "coverage": cov_env}})
        # spec §11: every receipt must be covered by a declared plan step
        plan_step_ids = {s.get("step/id") for s in (plan.get("steps") or [])}
        for rkey in ("validation-receipts", "capability-receipts", "execution-receipts"):
            for r in (bundle.get(rkey) or []):
                if r.get("step/id") not in plan_step_ids:
                    issues.append({"issue/code": "unexpected-receipt",
                                   "issue/details": {"step/id": r.get("step/id")}})
        supplied = bundle.get("claim")
        derived = derive_claim(bundle)
        # informational metadata (:claim/scope, :claim/does-not-establish) is a
        # fixed property of every claim and never enters the parity core
        INFO_KEYS = {"claim/scope", "claim/does-not-establish"}
        supplied_core = {k: v for k, v in (supplied or {}).items()
                         if k not in ("claim/json-root",) and k not in INFO_KEYS}
        derived_core = {k: v for k, v in (derived or {}).items()
                        if k not in ("claim/json-root",) and k not in INFO_KEYS}
        if derived is not None and supplied is not None and derived_core != supplied_core:
            issues.append({"issue/code": "derived-claim-mismatch",
                           "issue/details": {"derived": derived_core, "supplied": supplied_core}})
        if supplied is not None and supplied.get("claim/json-root") and derived is not None:
            if supplied["claim/json-root"] != sha256_hex(canonical_json_str(derived_core).encode()):
                issues.append({"issue/code": "claim-json-root-mismatch", "issue/details": {}})

    derived = derive_claim(bundle) if not issues else None
    status = ("unsupported-version" if version != BUNDLE_SCHEMA_VERSION
              else "pass" if (not issues and derived) else "rejected")
    return {
        "verification/status": status,
        "outcome/class": "claimable" if derived else "not-claimable",
        "claimable?": bool(derived),
        "derived-claim/root": (derived or {}).get("claim/json-root"),
        "issue-codes": [i["issue/code"] for i in issues],
    }


def _object_pairs(pairs: list) -> dict:
    """Reject duplicate keys (CR-004): a serialization ambiguity that can
    silently change a root.  All verifiers agree on rejection."""
    seen: dict[str, int] = {}
    for k, _ in pairs:
        seen[k] = seen.get(k, 0) + 1
        if seen[k] > 1:
            raise ValueError(f"duplicate JSON key: {k}")
    return dict(pairs)


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: bundle_verify.py <bundle.json>", file=sys.stderr)
        sys.exit(2)
    text = Path(sys.argv[1]).read_text("utf-8")
    try:
        bundle = json.loads(text, object_pairs_hook=_object_pairs)
    except ValueError as e:
        issue = "duplicate-json-key" if "duplicate JSON key" in str(e) else "malformed-json"
        print(json.dumps({
            "verification/status": "rejected",
            "outcome/class": "not-claimable",
            "claimable?": False,
            "derived-claim/root": None,
            "issue-codes": [issue],
        }, indent=2))
        return
    result = verify_bundle(bundle)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
