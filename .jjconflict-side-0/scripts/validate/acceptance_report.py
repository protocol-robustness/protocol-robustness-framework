"""Composed acceptance report (mirrors resolver-sim.evidence.acceptance).

Artifact validity is not a sufficient acceptance decision.  The accepted
registry must pass distinct, independently-reported stages:

    schema-valid -> content-integrity-valid -> registry-membership
    -> required-chain -> publisher-commitment -> file-integrity

Stages:
    content-integrity      content-addressed artifact validity.  In the Python
                           acceptance pipeline this is enforced on the JVM side
                           by resolver-sim.evidence.artifact/verify-artifact
                           (canonical preimage + content hash); the publisher
                           commitment and file-integrity stages below bind the
                           on-disk bytes, and the golden commitment-preimage
                           fixture locks the cross-language serialization.
    registry-membership    the artifact has an entry in the accepted registry
                           and the registry is schema-valid.
    required-chain         test-run / test-summary / claimable-classification /
                           results-artifact are all present.
    publisher-commitment   a publisher envelope commits to the artifact set
                           under a key authorised by the declared policy
                           (scripts/validate/publisher_commitment.py).
    file-integrity         every artifact file on disk matches its registry
                           binding (sha256 re-hashed from disk).

The report is fail-closed: any missing stage is an unexplained failure.
"""

from __future__ import annotations

from typing import Any

STAGES = [
    "content-integrity",
    "registry-membership",
    "required-chain",
    "publisher-commitment",
    "file-integrity",
]


def stage_report(stage_result: Any | None) -> dict:
    """Normalize one stage result into {:valid? bool :reason str :details dict}."""
    if stage_result is None:
        return {"valid?": False, "reason": "stage-missing", "details": {}}
    if not isinstance(stage_result, dict):
        return {"valid?": False, "reason": "stage-malformed", "details": {"value": stage_result}}
    return {
        "valid?": bool(stage_result.get("valid?")),
        "reason": stage_result.get("reason", "ok"),
        "details": stage_result.get("details", {}),
    }


def acceptance_report(stages: dict) -> dict:
    """Compose a per-stage acceptance report.

    ``stages`` maps stage name -> stage result dict.  Missing stages are
    recorded as fail-closed ``stage-missing``.  Returns the composed map with
    ``accepted?`` first and every stage present.
    """
    normalized = {
        s: stage_report(stages.get(s))
        for s in STAGES
    }
    return {
        "accepted?": all(v["valid?"] for v in normalized.values()),
        **normalized,
    }
