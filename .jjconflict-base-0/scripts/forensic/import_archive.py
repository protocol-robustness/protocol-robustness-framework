#!/usr/bin/env python3
"""Import a forensic run bundle from a portable tar.gz archive.

Usage:
    python3 scripts/forensic/import_archive.py <archive> [--output <dir>]
"""

from __future__ import annotations

import json
import os
import sys
import tarfile
from pathlib import Path

PRF_RUNS_ROOT = Path(os.environ.get("PRF_RUNS_ROOT", "~/prf-runs")).expanduser()


def import_bundle(archive_path: str | Path,
                  output_dir: str | Path | None = None) -> str:
    archive_path = Path(archive_path).expanduser().resolve()
    if not archive_path.exists():
        raise FileNotFoundError(f"Archive not found: {archive_path}")

    if output_dir is None:
        output_dir = PRF_RUNS_ROOT
    output_dir = Path(output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with tarfile.open(str(archive_path), "r:gz") as tar:
        mani = tar.extractfile("export-manifest.json")
        if mani:
            manifest = json.loads(mani.read())
            print(f"  bundle-id: {manifest.get('export/bundle-id', '?')}",
                  file=sys.stderr)
        tar.extractall(path=str(output_dir), filter="data")

    # Determine the extracted directory name
    extracted = output_dir / archive_path.stem.replace(".tar", "")
    if not extracted.exists():
        # The archive might use a different root name; find the first dir
        for f in output_dir.iterdir():
            if f.is_dir() and f.name != archive_path.stem:
                extracted = f
                break
    file_count = len(list(extracted.rglob("*"))) if extracted.exists() else 0
    print(f"  imported to {extracted}  ({file_count} files)", file=sys.stderr)
    return str(extracted)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Import forensic bundle from tar.gz")
    parser.add_argument("archive", help="Path to tar.gz archive")
    parser.add_argument("--output", "-o", help="Output directory (default: ~/prf-runs)")
    args = parser.parse_args()
    try:
        path = import_bundle(args.archive, args.output)
        print(path)
    except Exception as e:
        print(f"Import failed: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
