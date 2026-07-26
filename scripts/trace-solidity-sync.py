#!/usr/bin/env python3
"""
trace-solidity-sync — Synchronise CDRS traces from the Clojure simulation repo
into the sew-protocol Solidity repo.

Usage:
  ./scripts/trace-solidity-sync.py [--sew-repo <path>] [--manifest <path>]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


def parse_edn_entries(path: Path) -> list[dict[str, Any]]:
    raw = path.read_text("utf-8")
    entries = []
    i = 0
    while i < len(raw):
        idx = raw.find("{:id", i)
        if idx == -1:
            break
        if idx > 0 and raw[idx - 1] not in " \t\n\r[(":
            i = idx + 4
            continue
        depth = 0
        j = idx
        while j < len(raw):
            if raw[j] == "{":
                depth += 1
            elif raw[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = raw[idx : j + 1]
        entry = {}
        for m in re.finditer(r':([\w-]+)\s+"((?:[^"\\]|\\.)*)"', block):
            entry[m.group(1)] = m.group(2)
        if entry:
            entries.append(entry)
        i = j + 1
    return entries


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Sync traces to sew-protocol")
    parser.add_argument("--sew-repo", default=None)
    parser.add_argument("--manifest", default="etc/trace-solidity-manifest.edn")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent

    manifest_path = (repo_root / args.manifest).resolve()
    if not manifest_path.exists():
        print(f"ERROR: manifest not found at {manifest_path}")
        sys.exit(1)

    raw_manifest = manifest_path.read_text("utf-8")
    m = re.search(r':sew-repo-root\s+"([^"]+)"', raw_manifest)
    default_sew = m.group(1) if m else "../sew-protocol"

    sew_str = args.sew_repo or os.environ.get("SEW_SOLIDITY_PATH") or default_sew
    sew_repo = Path(sew_str).resolve()
    if not sew_repo.is_dir():
        print(f"ERROR: sew-protocol repo not found at {sew_repo}")
        sys.exit(1)

    entries = parse_edn_entries(manifest_path)

    print(f"Clojure repo: {repo_root}")
    print(f"Sew repo:     {sew_repo}")
    print(f"Manifest:     {manifest_path}")
    print(f"Entries:      {len(entries)}")
    print()

    total = len(entries)
    copied = 0
    generated = 0
    failed = 0

    for entry in entries:
        entry_id = entry.get("id", "unknown")
        source = entry.get("source")
        scenario = entry.get("scenario")
        dest = entry.get("destination")

        if not dest:
            print(f"  [SKIP] {entry_id} — missing :destination")
            continue

        abs_source = (repo_root / source).resolve() if source else None
        abs_dest = (sew_repo / dest).resolve()

        # Generate source trace if needed
        if abs_source and not abs_source.exists() and scenario:
            print(f"  [GEN]  {entry_id}")
            abs_scenario = (repo_root / scenario).resolve()
            if not abs_scenario.exists():
                print(f"  [FAIL] {entry_id} — scenario file not found: {abs_scenario}")
                failed += 1
                continue

            abs_source.parent.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(
                ["clojure", "-M:trace-export", str(abs_scenario), str(abs_source)],
                cwd=repo_root,
                capture_output=True,
                text=True,
                timeout=300,
            )
            if result.returncode != 0:
                print(f"  [FAIL] {entry_id} — trace-export failed")
                print(f"         stderr: {result.stderr.strip()}")
                failed += 1
                continue
            generated += 1
            src_sha = sha256_file(abs_source)
            print(f"    sha256: {src_sha}")

        elif abs_source and not abs_source.exists():
            print(f"  [FAIL] {entry_id} — source not found: {source}")
            failed += 1
            continue

        if not abs_source or not abs_source.exists():
            print(f"  [SKIP] {entry_id} — no source available")
            continue

        # Copy to destination
        print(f"  [CP]   {entry_id} → {dest}")
        abs_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(abs_source, abs_dest)

        src_sha = sha256_file(abs_source)
        dst_sha = sha256_file(abs_dest)
        print(f"    source sha256:      {src_sha}")
        print(f"    destination sha256: {dst_sha}")
        if src_sha != dst_sha:
            print(f"    [WARN] SHA-256 mismatch after copy")

        copied += 1

    print()
    print("=== Summary ===")
    print(f"  Total entries: {total}")
    print(f"  Generated:     {generated}")
    print(f"  Copied:        {copied}")
    print(f"  Failed:        {failed}")

    if failed > 0:
        sys.exit(1)
    print("Sync complete.")


if __name__ == "__main__":
    main()
