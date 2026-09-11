#!/usr/bin/env python3
"""Export a forensic run bundle as a portable tar.gz archive.

Usage:
    python3 scripts/forensic/export.py <run-dir> [--output <path>]
"""

from __future__ import annotations

import json
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "forensic-export.v1"


def export_bundle(run_dir: str | Path,
                  output_path: str | Path | None = None) -> str:
    run_dir = Path(run_dir).expanduser().resolve()
    if not run_dir.exists():
        raise FileNotFoundError(f"Run directory not found: {run_dir}")

    if output_path is None:
        stem = f"forensic-run-{run_dir.name}.tar.gz"
        output_path = Path.cwd() / stem
    output_path = Path(output_path).expanduser().resolve()

    # Build manifest
    manifest: dict[str, Any] = {
        "export/schema-version": SCHEMA_VERSION,
        "export/timestamp": datetime.now(timezone.utc).isoformat(),
        "export/source": str(run_dir),
        "export/files": [str(f.relative_to(run_dir))
                         for f in sorted(run_dir.rglob("*")) if f.is_file()],
    }
    bundle = run_dir / "run-bundle-root.json"
    if bundle.exists():
        try:
            bd = json.loads(bundle.read_text())
            manifest["export/bundle-id"] = bd.get("bundle/id")
            manifest["export/bundle-hash"] = bd.get("bundle/hash")
        except Exception:
            pass

    mani_bytes = json.dumps(manifest, indent=2).encode("utf-8")

    with tarfile.open(str(output_path), "w:gz") as tar:
        mani_ti = tarfile.TarInfo(name="export-manifest.json")
        mani_ti.size = len(mani_bytes)
        tar.addfile(mani_ti, __import__("io").BytesIO(mani_bytes))
        tar.add(str(run_dir), arcname=run_dir.name)

    size = output_path.stat().st_size
    print(f"  exported {run_dir.name} → {output_path.name}  ({size} bytes)",
          file=sys.stderr)
    return str(output_path)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Export forensic bundle as tar.gz")
    parser.add_argument("run_dir", help="Path to forensic run directory")
    parser.add_argument("--output", "-o", help="Output archive path")
    args = parser.parse_args()
    try:
        path = export_bundle(args.run_dir, args.output)
        print(path)
    except Exception as e:
        print(f"Export failed: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
