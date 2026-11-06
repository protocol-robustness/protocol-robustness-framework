#!/usr/bin/env python3
"""Independent stdlib HTTP smoke client for the local researcher API demo.

This checks the documented workspace and signature-intent wire contract. It
does not create a signature, submit a contribution, or independently implement
PRF hashing or ratification.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request


ROOT_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def request_json(url, token=None, payload=None):
    headers = {"Accept": "application/json"}
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode("utf-8")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        try:
            document = json.load(error)
        finally:
            error.close()
        return error.code, document


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(base_url, target_root, token):
    base_url = base_url.rstrip("/")
    target_url = f"{base_url}/v1/targets/{target_root}"
    status, workspace = request_json(target_url)
    require(status == 200, f"workspace returned HTTP {status}: {workspace}")
    require(workspace.get("demoOnly") is True, "workspace is not marked demo-only")
    require(workspace.get("policyApprovalStatus") == "not-approved",
            "demo workspace must report policy approval as not-approved")
    require(workspace.get("target", {}).get("root") == target_root,
            "workspace target root does not match requested root")
    require(workspace.get("ratification", {}).get("operationalAuthority") is False,
            "ratification must not report operational authority")
    assessment = workspace.get("claimAssessment", {})
    require(assessment.get("assessmentSource") == "demo-fixture",
            "claim assessment must identify its fixture source")
    require(assessment.get("methodVerification") in {"verified", "unverified", "unavailable"},
            "claim assessment omitted method verification status")

    status, intent = request_json(
        f"{target_url}/contribution-intents", token,
        {"kind": "approve"})
    require(status == 200, f"intent returned HTTP {status}: {intent}")
    require(intent.get("targetRoot") == target_root,
            "intent is not bound to the requested target")
    require(intent.get("kind") == "approve" and intent.get("decision") == "approve",
            "approval intent has inconsistent contribution and decision kinds")
    require(intent.get("principalId") and intent.get("researcherId")
            and intent.get("signingKeyId"), "intent omitted authenticated signer binding")
    require(ROOT_RE.fullmatch(intent.get("outcomeRoot", "")) is not None,
            "intent outcomeRoot is not a canonical SHA-256 reference")
    require(intent.get("dissentReasonRequired") is False,
            "approval intent incorrectly requires a dissent reason")
    require(intent.get("requestRoot") == target_root
            and intent.get("authorisationId") == target_root,
            "intent signing fields do not bind the exact target")
    require(intent.get("reviewRoundRoot") == workspace["target"].get("roundRoot"),
            "intent signing fields do not bind the frozen review round")
    print(json.dumps({
        "status": "passed",
        "checks": ["workspace-boundary", "claim-assessment-separation",
                   "signer-bound-intent", "target-and-round-binding"],
        "targetRoot": target_root,
        "outcomeRoot": intent["outcomeRoot"],
    }, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8787")
    parser.add_argument("--target-root", required=True)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()
    if ROOT_RE.fullmatch(args.target_root) is None:
        parser.error("--target-root must be sha256:<64 lowercase hex>")
    try:
        run(args.url, args.target_root, args.token)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"researcher API smoke check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
