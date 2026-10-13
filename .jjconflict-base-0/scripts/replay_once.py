#!/usr/bin/env python3
"""Replay one fixture trace to JSON. Used by sim_diff.sh and git worktrees."""

from __future__ import annotations

import sys
from pathlib import Path

from trace_compare import replay_scenario, repo_root


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: replay_once.py <trace-or-scenario-path> <output.json> [cwd]", file=sys.stderr)
        sys.exit(2)
    trace_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2])
    cwd = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else repo_root()
    replay_scenario(trace_path.resolve(), out_path.resolve(), cwd=cwd)
    print(f"Wrote: {out_path}")


if __name__ == "__main__":
    main()
