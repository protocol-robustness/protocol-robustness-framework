#!/usr/bin/env python3
"""
Extract failures from suite-*.json files and write to .risk-{RUN_ID}.lines format.

This script reads suite result JSON files from the test-artifacts directory and
emits risk lines for any failures, making them visible in test-summary.json's
risk_digest. Called after suite runs complete.

Usage:
    python3 scripts/suite_failures_to_risk.py [--artifact-dir DIR] [--output FILE]

Output format (per line):
    severity|phase|code|message
"""

from __future__ import annotations
import argparse
import json
import sys
from pathlib import Path

def extract_suite_failures(artifact_dir: Path) -> list[str]:
    """Extract failure lines from suite-*.json files."""
    lines = []
    
    for suite_file in sorted(artifact_dir.glob("suite-*.json")):
        try:
            sdata = json.loads(suite_file.read_text())
        except Exception as e:
            print(f"Warning: Could not read {suite_file}: {e}", file=sys.stderr)
            continue
        
        suite_id = sdata.get("suite/id") or sdata.get("id", "unknown")
        
        # Extract errors (failed checks)
        errors = sdata.get("errors", [])
        for err in errors:
            severity = err.get("severity", "warning")
            key = err.get("key", "unclassified-error")
            scenario_id = err.get("scenario-id")
            
            # Build code: prefer scenario-specific, fallback to generic
            code = f"SUITE_FAILURE:{key}"
            if scenario_id:
                code = f"SUITE_FAILURE:{scenario_id}:{key}"
            
            # Truncate message for readability
            msg = err.get("message", "")
            if len(msg) > 200:
                msg = msg[:200] + "..."
            msg = msg.replace("\n", " ").replace("|", " ")
            
            lines.append(f"{severity}|{suite_id}|{code}|{msg}")
        
        # Extract warnings (failed checks with warning severity)
        warnings = sdata.get("warnings", [])
        for warn in warnings:
            key = warn.get("key", "unclassified-warning")
            scenario_id = warn.get("scenario-id")
            
            code = f"SUITE_WARNING:{key}"
            if scenario_id:
                code = f"SUITE_WARNING:{scenario_id}:{key}"
            
            msg = warn.get("message", "")
            if len(msg) > 200:
                msg = msg[:200] + "..."
            msg = msg.replace("\n", " ").replace("|", " ")
            
            lines.append(f"warning|{suite_id}|{code}|{msg}")
    
    return lines

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", default="results/test-artifacts")
    parser.add_argument("--output", help="Output file path (default: .risk-{RUN_ID}.lines in artifact-dir)")
    parser.add_argument("--run-id", help="Run ID for default output filename")
    args = parser.parse_args()
    
    artifact_dir = Path(args.artifact_dir)
    if not artifact_dir.exists():
        print(f"Error: Artifact directory not found: {artifact_dir}")
        return 1
    
    lines = extract_suite_failures(artifact_dir)
    
    if not lines:
        # Write empty file to indicate no failures
        output_path = Path(args.output) if args.output else artifact_dir / f".risk-{args.run_id or 'latest'}.lines"
        output_path.write_text("")
        print(f"No suite failures found. Wrote empty marker: {output_path}")
        return 0
    
    # Write output
    if args.output:
        output_path = Path(args.output)
    else:
        run_id = args.run_id or "latest"
        output_path = artifact_dir / f".risk-{run_id}.lines"
    
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n")
    
    print(f"Wrote {len(lines)} failure line(s) to {output_path}")
    return 0

if __name__ == "__main__":
    sys.exit(main())