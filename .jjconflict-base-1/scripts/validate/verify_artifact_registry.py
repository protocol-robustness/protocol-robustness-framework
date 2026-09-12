#!/usr/bin/env python3
"""
Verify the integrity of a test-artifacts.json registry bundle.
Checks: existence, SHA256 hashes, byte sizes, and run-id consistency.
"""

import argparse
import hashlib
import json
import pathlib
import sys

_EVIDENCE_DIR = pathlib.Path(__file__).resolve().parent.parent / "evidence"
sys.path.insert(0, str(_EVIDENCE_DIR))

from evidence_config import EvidenceConfig
from schema_validator import SchemaValidator
_cfg = EvidenceConfig()

def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def verify_registry(registry_path: pathlib.Path) -> bool:
    if not registry_path.exists():
        print(f"Error: Registry file not found: {registry_path}")
        return False
    
    try:
        registry = json.loads(registry_path.read_text())
    except json.JSONDecodeError as e:
        print(f"Error: Failed to parse registry JSON: {e}")
        return False

    # Structural validation first
    struct_errors = SchemaValidator().validate(registry)
    if struct_errors:
        print(f"Error: Registry is structurally invalid ({len(struct_errors)} error(s))")
        for e in struct_errors:
            print(f"  {e.path}: {e.message}")
        return False

    artifacts = registry.get("artifacts", [])
    
    all_ok = True
    
    # 1. Verify artifacts integrity
    registered_paths = set()
    for art in artifacts:
        # Resolve path relative to current working directory (project root)
        path = pathlib.Path(art["path"])
        registered_paths.add(str(path))
        if not path.exists():
            print(f"Error: Artifact file missing: {path}")
            all_ok = False
            continue
        
        # Verify hash
        actual_hash = sha256_file(path)
        if actual_hash != art["sha256"]:
            print(f"Error: Hash mismatch for {path}: expected {art['sha256']}, got {actual_hash}")
            all_ok = False
            
        # Verify bytes
        actual_bytes = path.stat().st_size
        if actual_bytes != art["bytes"]:
            print(f"Error: Byte size mismatch for {path}: expected {art['bytes']}, got {actual_bytes}")
            all_ok = False

    # Check for orphans in results/test-artifacts
    artifact_dir = registry_path.parent
    for p in artifact_dir.iterdir():
        if p.name == "test-artifacts.json": continue
        if str(p) not in registered_paths:
            print(f"Warning: Orphan file found in bundle: {p}")
            
    return all_ok

def main() -> int:
    ap = argparse.ArgumentParser(description="Verify artifact registry integrity.")
    ap.add_argument("registry_file", type=pathlib.Path, help="Path to test-artifacts.json")
    args = ap.parse_args()
    
    if verify_registry(args.registry_file):
        print(f"Success: Registry {args.registry_file} verified.")
        return 0
    else:
        print(f"Failure: Registry {args.registry_file} integrity check failed.")
        return 1

if __name__ == "__main__":
    sys.exit(main())
