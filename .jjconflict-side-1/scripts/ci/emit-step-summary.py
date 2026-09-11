#!/usr/bin/env python3
"""
Render a concise, human-readable failure summary for a CI step.

Reads structured result JSON produced by the test runners and writes BOTH:

  * a Markdown summary block to $GITHUB_STEP_SUMMARY (GitHub aggregates these
    onto the workflow run page), and
  * GitHub workflow annotations (::error / ::warning / ::notice) so failures
    surface on the run page and the PR Checks tab.

The script is a thin wrapper: it does not change how tests produce results. When
run outside GitHub Actions it prints the same summary to stdout and exits 0, so
local invocations are harmless.

Supported inputs (auto-detected by shape):

  * Suite/run summary JSON with ``passed``/``failed``/``scenario_count``
    (e.g. suites/*/actual/summary.json).
  * test-summary.json (test-summary.v2) with ``targets``/``overall_status``
    and optional ``risk_digest``.

Usage:
    emit-step-summary.py --label "Reference Suite v1" \
        --summary suites/reference-validation-v1/actual/summary.json \
        [--file FILE] [--gating]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


def _label(fn: str) -> str:
    # Never conflate the human label with a checked-in filename default.
    return fn or "CI"


def _read_summary(path: Path):
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        return None, f"summary file not found: {path}"
    except json.JSONDecodeError as e:
        return None, f"summary file is not valid JSON: {e}"
    return data, None


def _counts(data) -> dict | None:
    """Best-effort extraction of pass/fail counts for tabular display."""
    out = {}
    for k in ("passed", "failed", "scenario_count", "inconclusive", "failure_count", "target_count"):
        if k in data and isinstance(data[k], (int, float)):
            out[k] = int(data[k])
    if out:
        return out
    return None


def _target_failures(data) -> list[tuple[str, str]]:
    """Return (target, log_file) pairs for failing targets in test-summary.v2."""
    rows = []
    for t in data.get("targets", []):
        if isinstance(t, dict) and t.get("status") in ("fail", "error"):
            rows.append((t.get("target", "?"), t.get("log_file", "")))
    return rows


_FAIL_BLOCK_STARTS = re.compile(r"^((FAIL|ERROR) in \(.*\)|Uncaught exception|Syntax error|RuntimeException)")
_CAUSE_LINES = re.compile(r"^(expected:|actual:|  actual:|Compilation error|clojure\.lang\.|.*Exception.*)")
_TRAILING = re.compile(r"^ *at |^ *clojure\.|^ *resolver_|^ *scripts\.|^\s*$")


def _failure_detail(log_file: str) -> str | None:
    """Extract the first real failure block from a failing target's log file.

    This is what makes a red/amber run *actually explain itself*: the summary
    JSON only carries counts + a log path, so without reading the log the
    emitter could only print "N finding(s)". Reading it lets us surface the
    concrete expected/actual + exception text on the run page.
    """
    if not log_file:
        return None
    path = Path(log_file)
    if not path.exists():
        return None
    try:
        lines = path.read_text(errors="replace").splitlines()
    except OSError:
        return None

    blocks = []
    for i, line in enumerate(lines):
        if _FAIL_BLOCK_STARTS.match(line.strip()):
            block = [line.strip()]
            for nxt in lines[i + 1:]:
                ns = nxt.strip()
                if _FAIL_BLOCK_STARTS.match(ns):
                    break
                if _TRAILING.match(ns):
                    continue
                if _CAUSE_LINES.match(ns):
                    block.append(ns)
                    if ns.startswith("actual:") or ns.startswith("expected:"):
                        # keep the matching expected/actual pair, then stop
                        # the stack trace behind the first actual.
                        if len(block) >= 2 and block[-2].startswith("expected:"):
                            break
                        if ns.startswith("actual:"):
                            break
                if len(block) >= 6:
                    break
            blocks.append("\n    ".join(block))
            if len(blocks) >= 3:
                break
    if not blocks:
        return None
    return "\n\n".join(blocks)


def _risk_lines(data) -> list[tuple[str, str]]:
    """Extract risk digest lines (severity|phase|code|message) if present."""
    lines = []
    digest = data.get("risk_digest") or {}
    if isinstance(digest, dict):
        for severity, entries in digest.items():
            if not isinstance(entries, list):
                continue
            for e in entries:
                if isinstance(e, str):
                    lines.append((severity, e))
                elif isinstance(e, dict):
                    msg = e.get("message") or e.get("detail") or json.dumps(e)
                    lines.append((severity, f"{e.get('key') or e.get('code') or ''} {msg}".strip()))
    return lines


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--label", default="", help="Human label for the suite/run (e.g. 'Reference Suite v1').")
    ap.add_argument("--summary", required=True, help="Path to a result summary JSON file.")
    ap.add_argument("--gating", action="store_true",
                    help="Treat failures as workflow-blocking (emit ::error instead of ::warning).")
    ap.add_argument("--file", default="", help="Artifact/log path to reference in the summary.")
    args = ap.parse_args()

    label = _label(args.label)
    path = Path(args.summary)

    data, err = _read_summary(path)
    if data is None:
        # For non-gating informational runs, a missing summary is expected (the
        # runner may not emit one). Only a gating run treats absence as a failure.
        line = f"{label}: {err}"
        if args.gating:
            print(f"::error title=PRF failure summary::{line}")
        else:
            print(f"::notice title=PRF failure summary::{line}")
        print(f"{label}: no summary produced (skipping step summary)")
        return 1 if args.gating else 0

    counts = _counts(data)
    failed = None
    if counts:
        failed = counts.get("failed", counts.get("failure_count", 0))
    else:
        failed = 0 if data.get("overall_status") == "pass" else 1

    # test-summary.v2 lacks a "passed" key but carries per-target status.
    passed = None
    if counts and "passed" in counts:
        passed = counts["passed"]
    elif data.get("targets"):
        statuses = [t.get("status") for t in data.get("targets", []) if isinstance(t, dict)]
        passed = sum(1 for s in statuses if s in ("pass", "passed", "ok", "safe"))

    risk = _risk_lines(data)
    target_failures = _target_failures(data)
    failures_detail = [(_fail[0], _failure_detail(_fail[1])) for _fail in target_failures]

    has_findings = (failed or 0) > 0 or target_failures or risk
    total = counts.get("scenario_count", counts.get("total", counts.get("target_count", ""))) if counts else ""

    composed = []
    composed.append("### PRF CI — Failure Summary")
    if has_findings:
        composed.append(f"**{label}** — `{'RED · blocking' if args.gating else '⚠ findings (non-blocking)'}`")
    else:
        composed.append(f"**{label}** — `✓ passed`")
        composed.append("")
        composed.append("No failures detected.")
    composed.append("")

    if counts and total != "":
        passed_v = passed if passed is not None else counts.get("passed", "?")
        failed_v = failed
        composed.append(f"- passed: `{passed_v}`   failed: `{failed_v}`   total: `{total}`")
    if args.file:
        composed.append(f"- log/artifact: `{args.file}`")

    # Failing targets (test-summary.v2).
    if target_failures:
        composed.append("")
        composed.append("Failing targets:")
        for target, log in target_failures[:10]:
            composed.append(f"  - `{target}`" + (f"  (log: {log})" if log else ""))
        if len(target_failures) > 10:
            composed.append(f"  - … and {len(target_failures) - 10} more")

    # Real failure text pulled from each failing target's log file. This is the
    # detail that makes the run page actually explain what broke.
    if failures_detail:
        composed.append("")
        composed.append("Failure detail:")
        for target, detail in failures_detail:
            composed.append("")
            composed.append(f"### {target}")
            composed.append("```")
            composed.append(detail or "(no detail extracted)")
            composed.append("```")

    # Risk digest lines.
    if risk:
        composed.append("")
        composed.append("Detected findings:")
        for severity, msg in risk[:10]:
            badge = {"error": "✕", "warning": "⚠", "info": "ℹ"}.get(severity, "•")
            composed.append(f"  - {badge} {msg}")
        if len(risk) > 10:
            composed.append(f"  - … and {len(risk) - 10} more")

    summary_text = "\n".join(composed) + "\n"

    if os.environ.get("GITHUB_STEP_SUMMARY"):
        try:
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
                fh.write(summary_text)
        except OSError as e:
            print(f"warning: could not write step summary: {e}", file=sys.stderr)

    # GitHub annotations: red only for gating failures; findings are warnings.
    if has_findings:
        severity = "error" if args.gating else "warning"
        title = f"PRF finding: {label}"
        if args.gating:
            message = f"{failed} failure(s) in {label}"
            if args.file:
                message += f" (see {args.file})"
        else:
            top = "".join(msg for _sev, msg in risk[:1]) or f"{failed} finding(s)"
            message = top[:200]
        print(f"::{severity} title={title}::{message}")
        for _sev, msg in risk[:5]:
            print(f"::{severity} title={label}::{msg[:200]}")
        # Surface the concrete failure text so the annotation (PR Checks tab)
        # carries detail even before the run page is opened.
        for target, detail in failures_detail:
            if detail:
                snip = detail.replace("\n", " ")[:200]
                print(f"::{severity} title={label}: {target}::{snip}")
    else:
        print(f"::notice title={label}::{label} passed")

    # Human-readable stdout copy (also used outside CI).
    print(summary_text)
    return 1 if (has_findings and args.gating) else 0


if __name__ == "__main__":
    sys.exit(main())