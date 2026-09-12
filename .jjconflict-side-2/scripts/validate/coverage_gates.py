#!/usr/bin/env python3
"""Transition/guard coverage gates verification.

Extracted from test.sh.
"""

import argparse
import json
import os
import sys
from pathlib import Path

def category_of(action):
    s = str(action)
    if "create_escrow" in s:
        return "creation"
    if "raise_dispute" in s or "sender_cancel" in s or "recipient_cancel" in s:
        return "state-change"
    if "escalate_dispute" in s or "challenge_resolution" in s:
        return "escalation"
    if "execute_resolution" in s or "execute_pending_settlement" in s:
        return "resolution"
    if "auto_cancel_disputed" in s:
        return "timeout"
    if "automate_timed_actions" in s or "register_stake" in s:
        return "governance"
    if "release" in s:
        return "economic"
    return None

def main():
    parser = argparse.ArgumentParser(description="Verify transition/guard coverage gates.")
    parser.add_argument(
        "--artifact-dir",
        default=os.environ.get("ARTIFACT_DIR", "results/test-artifacts"),
        help="Path to artifacts directory"
    )
    parser.add_argument(
        "--max-unhit-transitions",
        type=int,
        default=int(os.environ.get("MAX_UNHIT_TRANSITIONS", "0")),
        help="Maximum allowed unhit transitions"
    )
    args = parser.parse_args()

    p = Path(args.artifact_dir) / "coverage.json"
    if not p.exists():
        print(f"Missing coverage artifact: {p}")
        sys.exit(1)

    try:
        obj = json.loads(p.read_text())
    except Exception as e:
        print(f"Failed to read or parse coverage JSON at {p}: {e}")
        sys.exit(1)

    unhit = obj.get("unhit-transitions", [])
    print(f"unhit-transitions={len(unhit)} threshold={args.max_unhit_transitions}")
    if len(unhit) > args.max_unhit_transitions:
        print("Coverage gate failed: too many unhit transitions")
        print("Unhit:", unhit)
        sys.exit(1)

    required_categories = {
        "creation", "state-change", "escalation", "resolution", "timeout", "governance", "economic"
    }
    hits = obj.get("transition-hit-freq", {})
    seen = {c for c in (category_of(k) for k in hits.keys()) if c}
    missing = sorted(required_categories - seen)
    if missing:
        print("Coverage gate failed: missing required transition categories:", missing)
        sys.exit(1)

    print("Coverage gates passed")
    return 0

if __name__ == "__main__":
    sys.exit(main())
