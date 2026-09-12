"""Integration tests for test-artifacts.v1.2 contract enforcement.

Tests:
  - SchemaValidator rejects v1 and v1.1, accepts v1.2
  - SchemaValidator rejects structurally invalid registries
  - Validators abort on structural failure before semantic checks
  - Producer emits v1.2 (not v1.1)
"""

from __future__ import annotations

import json
import sys
import pathlib
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "validate"))

from schema_validator import SchemaValidator


_VALID_REGISTRY = {
    "schema_version": "test-artifacts.v1.2",
    "contract_version": "evidence-contract.v1",
    "run_id": "20260714-120000",
    "generated_at": "2026-07-14T12:00:00+00:00",
    "generator": {"name": "test", "version": "v1"},
    "root_dir": ".",
    "artifacts": [
        {
            "id": "test-a",
            "kind": "summary",
            "path": "test.json",
            "schema_version": "test.v1",
            "sha256": "a" * 64,
            "importance": "CORE",
            "dependencies": [],
            "verifies_against": [],
        }
    ],
}

_PASS = 0
_FAIL = 0


def check(name: str, ok: bool, detail: str = ""):
    global _PASS, _FAIL
    if ok:
        _PASS += 1
        print(f"  PASS: {name}")
    else:
        _FAIL += 1
        print(f"  FAIL: {name} — {detail}")


# ── SchemaValidator ──────────────────────────────────────────────────────

def test_schema_version_rejection():
    v = SchemaValidator()
    for bad_ver in ("test-artifacts.v1", "test-artifacts.v1.1", "test-artifacts.v1.0", "unknown"):
        reg = dict(_VALID_REGISTRY, schema_version=bad_ver)
        try:
            v.validate(reg)
            check(f"SCH-1: rejects {bad_ver}", False, "no error raised")
        except ValueError:
            check(f"SCH-1: rejects {bad_ver}", True)


def test_valid_v1_2():
    v = SchemaValidator()
    errors = v.validate(_VALID_REGISTRY)
    check("SCH-2: accepts valid v1.2", len(errors) == 0, str(errors))


def test_missing_run_id():
    v = SchemaValidator()
    reg = dict(_VALID_REGISTRY)
    del reg["run_id"]
    errors = v.validate(reg)
    check("SCH-3: missing run_id", len(errors) > 0)


def test_missing_artifacts():
    v = SchemaValidator()
    reg = dict(_VALID_REGISTRY, artifacts=[])
    errors = v.validate(reg)
    check("SCH-4: empty artifacts fails minItems", len(errors) > 0)


def test_unknown_top_level_field():
    v = SchemaValidator()
    reg = dict(_VALID_REGISTRY, stray_field="bad")
    errors = v.validate(reg)
    check("SCH-5: unknown top-level field", len(errors) > 0)


def test_unknown_artifact_field():
    v = SchemaValidator()
    art = dict(_VALID_REGISTRY["artifacts"][0], stray="bad")
    reg = dict(_VALID_REGISTRY, artifacts=[art])
    errors = v.validate(reg)
    check("SCH-6: unknown artifact field", len(errors) > 0)


def test_bad_sha256_pattern():
    v = SchemaValidator()
    art = dict(_VALID_REGISTRY["artifacts"][0], sha256="not-hex")
    reg = dict(_VALID_REGISTRY, artifacts=[art])
    errors = v.validate(reg)
    check("SCH-7: bad sha256 pattern", len(errors) > 0)


def test_bad_importance():
    v = SchemaValidator()
    art = dict(_VALID_REGISTRY["artifacts"][0], importance="INVALID")
    reg = dict(_VALID_REGISTRY, artifacts=[art])
    errors = v.validate(reg)
    check("SCH-8: bad importance enum", len(errors) > 0)


def test_missing_artifact_sha256():
    v = SchemaValidator()
    art = dict(_VALID_REGISTRY["artifacts"][0])
    del art["sha256"]
    reg = dict(_VALID_REGISTRY, artifacts=[art])
    errors = v.validate(reg)
    check("SCH-9: missing sha256", len(errors) > 0)


def test_missing_artifact_importance():
    v = SchemaValidator()
    art = dict(_VALID_REGISTRY["artifacts"][0])
    del art["importance"]
    reg = dict(_VALID_REGISTRY, artifacts=[art])
    errors = v.validate(reg)
    check("SCH-10: missing importance", len(errors) > 0)


# ── Producer: write_scenario_run_manifest.py ─────────────────────────────

def test_producer_emits_v1_2():
    """Verify write_scenario_run_manifest.py emits v1.2."""
    import subprocess
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        env = {**__import__("os").environ, "PYTHONPATH": "scripts/evidence:scripts/validate"}
        result = subprocess.run(
            [sys.executable, "scripts/evidence/write_scenario_run_manifest.py",
             "--output-dir", td, "--artifact-dir", td,
             "--status", "pass", "--suite", "test-unit"],
            capture_output=True, text=True, cwd=pathlib.Path(__file__).resolve().parent.parent.parent,
            env=env,
        )
        check("PROD-1: exit code 0", result.returncode == 0, result.stderr)
        if result.returncode == 0:
            out_line = result.stdout.strip().split("\n")[-1]
            check("PROD-2: emits v1.2 message", "v1.2" in out_line, out_line)
            registry_path = td_path / "test-artifacts.json"
            if registry_path.exists():
                reg = json.loads(registry_path.read_text())
                check("PROD-3: schema_version is v1.2",
                      reg.get("schema_version") == "test-artifacts.v1.2",
                      reg.get("schema_version"))
                check("PROD-4: generator version v1.2",
                      reg.get("generator", {}).get("version") == "v1.2",
                      str(reg.get("generator")))
            else:
                check("PROD-5: registry file written", False, "file not found")
        else:
            check("PROD-6: error output", False, result.stderr)


# ── Validator integration ────────────────────────────────────────────────

def test_validate_artifact_registry_catches_structural():
    """validate_artifact_registry.py rejects structurally invalid v1.1 registry."""
    import subprocess
    with tempfile.TemporaryDirectory() as td:
        td_path = pathlib.Path(td)
        # Write a v1.1-style registry (missing run_id)
        bad_reg = {
            "schema_version": "test-artifacts.v1.2",
            "contract_version": "evidence-contract.v1",
            # no run_id
            "generated_at": "2026-07-14T12:00:00+00:00",
            "artifacts": [
                {"id": "test-run", "kind": "run-manifest", "path": "test.json",
                 "schema_version": "test-run.v1", "sha256": "a" * 64,
                 "importance": "CORE", "dependencies": [], "verifies_against": []}
            ],
        }
        (td_path / "test-artifacts.json").write_text(json.dumps(bad_reg))
        (td_path / "test-run.json").write_text(json.dumps({
            "schema_version": "test-run.v1", "run_id": "r1"}))
        (td_path / "test-summary.json").write_text(json.dumps({
            "schema_version": "test-summary.v2", "run_id": "r1"}))
        (td_path / "claimable-classification.json").write_text(json.dumps({
            "schema_version": "claimable-classification.v2"}))

        env = {**__import__("os").environ, "PYTHONPATH": "scripts/evidence:scripts/validate"}
        result = subprocess.run(
            [sys.executable, "scripts/validate/validate_artifact_registry.py",
             "--registry", str(td_path / "test-artifacts.json"),
             "--run-manifest", str(td_path / "test-run.json"),
             "--summary", str(td_path / "test-summary.json"),
             "--claimable", str(td_path / "claimable-classification.json")],
            capture_output=True, text=True,
            cwd=pathlib.Path(__file__).resolve().parent.parent.parent,
            env=env,
        )
        check("VAL-1: structural failure detected",
              result.returncode != 0,
              f"exit={result.returncode} out={result.stdout} err={result.stderr}")
        check("VAL-2: STRUCTURAL FAILURE prefix in output",
              "STRUCTURAL FAILURE" in result.stdout,
              result.stdout)


# ── run ──────────────────────────────────────────────────────────────────

def main():
    print("=== test-artifact-contract-v1.2 integration tests ===\n")

    print("--- SchemaValidator (structural) ---")
    test_schema_version_rejection()
    test_valid_v1_2()
    test_missing_run_id()
    test_missing_artifacts()
    test_unknown_top_level_field()
    test_unknown_artifact_field()
    test_bad_sha256_pattern()
    test_bad_importance()
    test_missing_artifact_sha256()
    test_missing_artifact_importance()

    print("\n--- Producer integration ---")
    test_producer_emits_v1_2()

    print("\n--- Validator integration ---")
    test_validate_artifact_registry_catches_structural()

    print(f"\n=== {_PASS} passed, {_FAIL} failed ===")
    return 1 if _FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
