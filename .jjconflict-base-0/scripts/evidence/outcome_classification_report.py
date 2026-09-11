#!/usr/bin/env python3
"""Outcome classification report for test.sh all mode.
Reads the test-summary.json produced by the 'all' target and prints
gate/test status and Monte Carlo model findings.

Usage: outcome_classification_report.py <artifact-file>
"""

import json
import sys
from pathlib import Path


def main():
    if len(sys.argv) < 2:
        print("Usage: outcome_classification_report.py <artifact-file>")
        sys.exit(0)

    artifact = Path(sys.argv[1])
    if not artifact.exists():
        print("No test summary found; skipping classification report.")
        return

    data = json.loads(artifact.read_text())
    targets = data.get("targets", [])

    hard_fail_targets = [t for t in targets if t.get("status") == "fail"]
    print("1) Gate/Test status")
    if hard_fail_targets:
        print(f"   FAIL: {len(hard_fail_targets)} failing target(s)")
        for t in hard_fail_targets:
            print(f"   - {t.get('target')} (exit={t.get('exit_code')})")
    else:
        print("   PASS: all executed targets passed")

    mc = next((t for t in targets if t.get("target") == "monte-carlo"), None)
    print("\n2) Model findings (non-gating diagnostics)")
    if not mc:
        print("   Monte Carlo target not run in this mode.")
        return

    log_path = Path(mc.get("log_file", ""))
    if not log_path.exists():
        print("   Monte Carlo log missing; cannot summarize findings.")
        return

    txt = log_path.read_text(errors="ignore")
    claim_fails = txt.count("❌")
    claim_pass = txt.count("✅")

    print(f"   Indicators in Monte Carlo output: ✅={claim_pass}, ❌={claim_fails}")
    print("   Note: these are model/theory outcome signals, not unit-test assertion failures.")


if __name__ == "__main__":
    main()
