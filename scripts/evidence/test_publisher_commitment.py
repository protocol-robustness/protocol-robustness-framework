"""Tests for the publisher commitment gate (test-artifacts acceptance stage 3).

Covers the golden commitment-preimage fixture, a valid sign/verify round-trip,
and the full set of adversarial tamper cases (signature transplant, copied
digest, unknown algorithm/key, revoked or mis-bound keys, malformed signature,
unknown envelope fields, old manifest version, policy mismatch) plus the wiring
of the gate into validate_artifact_registry.py.

Run: python3 scripts/evidence/test_publisher_commitment.py
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile

import nacl.signing

_EVIDENCE_DIR = pathlib.Path(__file__).resolve().parent
_VALIDATE_DIR = _EVIDENCE_DIR.parent / "validate"
for _d in (_EVIDENCE_DIR, _VALIDATE_DIR):
    if str(_d) not in sys.path:
        sys.path.insert(0, str(_d))

from publisher_commitment import (  # noqa: E402
    REASON_COMMITMENT_MISMATCH,
    REASON_NOT_AUTHORISED,
    REASON_POLICY_MISMATCH,
    REASON_SIGNATURE_INVALID,
    REASON_UNKNOWN_ALGORITHM,
    REASON_UNKNOWN_FIELD,
    REASON_UNKNOWN_KEY,
    REASON_UNSUPPORTED_VERSION,
    REASON_VALID,
    commitment_digest,
    policy_hash,
    verify_publisher_commitment,
)
from sign_publisher_commitment import sign_publisher_commitment  # noqa: E402

_REPO_ROOT = _EVIDENCE_DIR.parent.parent
_FIXTURE = _EVIDENCE_DIR / "fixtures" / "publisher-commitment-golden-v1.json"
_POLICY_ID = "artifact-publish-policy.v1"
_PUBLISHER_ID = "test-publisher"
_KEY_ID = "test-key-001"

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


# ── fixture helpers ───────────────────────────────────────────────────────────

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def make_policy(pub_hex: str, status: str = "active", key_id: str = _KEY_ID) -> dict:
    return {
        "schema_version": "publisher-policy.v1",
        "policy_id": _POLICY_ID,
        "trusted_keys": [
            {
                "publisher_id": _PUBLISHER_ID,
                "key_id": key_id,
                "algorithm": "ed25519",
                "public_key_hex": pub_hex,
                "status": status,
            }
        ],
    }


def _artifact(id_: str, path: str, sha: str, importance: str = "CORE", **extra) -> dict:
    art = {
        "id": id_,
        "kind": "evidence",
        "path": path,
        "schema_version": "evidence.v1",
        "sha256": sha,
        "importance": importance,
        "dependencies": [],
        "verifies_against": [],
    }
    art.update(extra)
    return art


def make_bundle(tmp: pathlib.Path, run_id: str = "run-test-001", **registry_overrides):
    """Write a minimally valid bundle (including the canonical results artifact)
    and return its parts."""
    tmp.mkdir(parents=True, exist_ok=True)

    run = {"schema_version": "test-run.v1", "run_id": run_id}
    summary = {
        "schema_version": "test-summary.v2",
        "run_id": run_id,
        "input_versions": {"test_run": "test-run.v1"},
        "shortfall_exposure": {
            "shortfall_related_scenarios": ["S01"],
            "partial_liquidity_enabled_scenarios": [],
            "rounding_policy": "floor-to-asset-decimals.v1",
        },
    }
    claimable = {
        "schema_version": "claimable-classification.v2",
        "run_id": run_id,
        "shortfall_policy": {
            "mode": "single-scenario",
            "allocation": "pro-rata",
            "rounding_policy": "floor-to-asset-decimals.v1",
        },
    }
    results = {
        "schema_version": "results-artifact.v1",
        "run_id": run_id,
        "results": {"scenario_id": "S01", "outcome": "pass"},
    }
    (tmp / "test-run.json").write_text(json.dumps(run))
    (tmp / "test-summary.json").write_text(json.dumps(summary))
    (tmp / "claimable-classification.json").write_text(json.dumps(claimable))
    (tmp / "results-artifact.json").write_text(json.dumps(results))

    run_p = tmp / "test-run.json"
    registry = {
        "schema_version": "test-artifacts.v1.2",
        "contract_version": "evidence-contract.v1",
        "run_id": run_id,
        "generated_at": "2026-08-06T12:00:00+00:00",
        "generator": {"name": "test", "version": "v1"},
        "root_dir": ".",
        "run_manifest": {
            "path": str(run_p),
            "schema_version": "test-run.v1",
            "sha256": sha256_file(run_p),
        },
        "artifacts": [
            _artifact("test-run", "test-run.json", sha256_file(tmp / "test-run.json"),
                      schema_version="test-run.v1"),
            _artifact("test-summary", "test-summary.json", sha256_file(tmp / "test-summary.json"),
                       schema_version="test-summary.v2",
                       input_versions={"test_run": "test-run.v1"}),
            _artifact("claimable-classification", "claimable-classification.json",
                      sha256_file(tmp / "claimable-classification.json"),
                      schema_version="claimable-classification.v2"),
            _artifact("results-artifact", "results-artifact.json",
                      sha256_file(tmp / "results-artifact.json"),
                      kind="results-artifact",
                      schema_version="results-artifact.v1",
                      extensions={"run_id": run_id}),
        ],
    }
    registry.update(registry_overrides)
    (tmp / "test-artifacts.json").write_text(json.dumps(registry))

    sk = nacl.signing.SigningKey.generate()
    pub_hex = sk.verify_key.encode().hex()
    seed_hex = sk.encode().hex()
    policy = make_policy(pub_hex)
    envelope = sign_publisher_commitment(
        registry,
        run_p,
        policy,
        seed_hex,
        _PUBLISHER_ID,
        _KEY_ID,
    )
    (tmp / "publication.json").write_text(json.dumps(envelope))
    return {
        "tmp": tmp,
        "registry": registry,
        "envelope": envelope,
        "policy": policy,
        "seed_hex": seed_hex,
        "sk": sk,
        "run_p": run_p,
    }


def _public_key_hex(policy: dict) -> str:
    return policy["trusted_keys"][0]["public_key_hex"]


def verify_with(parts: dict, registry=None, envelope=None, policy=None):
    """Verify with explicit parts; default policy is the one used to sign."""
    registry = registry if registry is not None else parts["registry"]
    envelope = envelope if envelope is not None else parts["envelope"]
    policy = policy if policy is not None else parts["policy"]
    return verify_publisher_commitment(
        registry,
        parts["run_p"],
        envelope,
        policy,
        base_dir=parts["tmp"],
    )


# ── tests ─────────────────────────────────────────────────────────────────────

def test_golden_fixture():
    fx = json.loads(_FIXTURE.read_text())
    actual = commitment_digest(fx["preimage"])
    check("GOLD-1: commitment digest matches golden fixture",
          actual == fx["expected_sha256"],
          f"got {actual}, expected {fx['expected_sha256']}")


def test_valid_signature_over_unchanged_registry():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        r = verify_with(parts)
        check("VALID-1: accepted", r["accepted"] is True, str(r))
        check("VALID-2: reason is publisher-signature-valid",
              r["reason"] == REASON_VALID, r["reason"])
        check("VALID-3: envelope includes no artifact list (verifier reconstructs)",
              "artifacts" not in parts["envelope"], str(parts["envelope"].keys()))


def test_artifact_modified_after_signing():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        (parts["tmp"] / "test-summary.json").write_text('{"schema_version": "test-summary.v2"}')
        r = verify_with(parts)
        check("MOD-1: artifact modified after signing rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_artifact_path_changed_same_hash():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_bundle(tmp)
        orig = (tmp / "test-summary.json").read_bytes()
        (tmp / "copy.json").write_bytes(orig)
        reg = dict(parts["registry"])
        reg["artifacts"] = [
            a if a["id"] != "test-summary"
            else {**a, "path": "copy.json"} for a in reg["artifacts"]
        ]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        r = verify_with(parts, registry=reg)
        check("PATH-1: path change with identical hash rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_artifact_added_or_removed():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_bundle(tmp)
        (tmp / "extra.json").write_text('{"schema_version": "extra.v1"}')
        reg = dict(parts["registry"])
        reg["artifacts"] = reg["artifacts"] + [
            _artifact("extra", "extra.json", sha256_file(tmp / "extra.json"))
        ]
        r = verify_with(parts, registry=reg)
        check("SET-1: artifact added rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))

        reg2 = dict(parts["registry"])
        reg2["artifacts"] = [a for a in reg2["artifacts"] if a["id"] != "test-summary"]
        r2 = verify_with(parts, registry=reg2)
        check("SET-2: artifact removed rejected",
              r2["reason"] == REASON_COMMITMENT_MISMATCH, str(r2))


def test_artifact_reordered_is_canonical():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        reversed_artifacts = list(reversed(parts["registry"]["artifacts"]))
        reg = dict(parts["registry"], artifacts=reversed_artifacts)
        # Canonical ordering: the commitment must be identical regardless of
        # the declared artifact array order, so reordering is not a forgery
        # vector and cannot change an accepted bundle.
        pre1 = build_preimage_like(parts["registry"])
        pre2 = build_preimage_like(reg)
        check("CANON-1: reordering artifacts is neutralised by canonical ordering",
              commitment_digest(pre1) == commitment_digest(pre2), "")
        r = verify_with(parts, registry=reg)
        check("CANON-2: reordered (content-identical) registry still accepted",
              r["accepted"] is True, str(r))


def build_preimage_like(registry):
    """Reproduce the preimage the verifier builds (for the canonical test)."""
    from publisher_commitment import commitment_preimage, PUBLISHER_MANIFEST_SCHEMA
    tmp = pathlib.Path(registry["run_manifest"]["path"]).parent
    shas = {a["id"]: sha256_file(tmp / a["path"]) for a in registry["artifacts"]}
    return commitment_preimage(
        registry,
        registry["run_manifest"]["path"],
        sha256_file(tmp / "test-run.json"),
        _POLICY_ID,
        "0" * 64,
        shas,
    )


def test_importance_changed():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_bundle(tmp)
        reg = dict(parts["registry"])
        reg["artifacts"] = [
            {**a, "importance": "DIAGNOSTIC"} if a["id"] == "test-summary" else a
            for a in reg["artifacts"]
        ]
        r = verify_with(parts, registry=reg)
        check("IMP-1: importance changed rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_run_manifest_changed():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        (parts["tmp"] / "test-run.json").write_text('{"schema_version": "test-run.v1"}')
        r = verify_with(parts)
        check("RUN-1: run manifest modified rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_run_id_substituted():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        reg = dict(parts["registry"], run_id="other-run")
        r = verify_with(parts, registry=reg)
        check("RUNID-1: run_id substituted rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_signature_copied_from_another_run():
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        a = make_bundle(root / "a", run_id="run-a")
        b = make_bundle(root / "b", run_id="run-b")
        # Re-sign b's digest with a's key material, then transplant the
        # signature: envelope carries b's correct claimed digest but the
        # signature from run a must fail.
        env_b = dict(b["envelope"])
        env_b["signature_hex"] = a["envelope"]["signature_hex"]
        r = verify_publisher_commitment(
            b["registry"],
            b["run_p"],
            env_b,
            b["policy"],
            base_dir=b["tmp"],
        )
        check("TRANSPLANT-1: signature copied from another run rejected",
              r["reason"] == REASON_SIGNATURE_INVALID, str(r))


def test_digest_copied_into_envelope_not_matching():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"])
        env["claimed_digest_hex"] = "0" * 64
        r = verify_with(parts, envelope=env)
        check("DIGEST-1: copied digest rejected as commitment mismatch",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_unknown_algorithm():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], algorithm="sha256withrsa")
        r = verify_with(parts, envelope=env)
        check("ALG-1: unknown algorithm rejected",
              r["reason"] == REASON_UNKNOWN_ALGORITHM, str(r))


def test_unknown_key():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], key_id="no-such-key")
        r = verify_with(parts, envelope=env)
        check("KEY-1: unknown key rejected",
              r["reason"] == REASON_UNKNOWN_KEY, str(r))


def test_unauthorised_or_revoked_key():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], publisher_id="other-publisher")
        r = verify_with(parts, envelope=env)
        check("AUTH-1: publisher_id mismatch rejected",
              r["reason"] == REASON_NOT_AUTHORISED, str(r))

        policy_revoked = make_policy(_public_key_hex(parts["policy"]), status="revoked")
        # Sign under the revoked policy so the committed policy hash matches and
        # the revoked-status authorisation check is what rejects the bundle.
        env_revoked = sign_publisher_commitment(
            parts["registry"], parts["run_p"], policy_revoked,
            parts["seed_hex"], _PUBLISHER_ID, _KEY_ID,
        )
        r2 = verify_publisher_commitment(
            parts["registry"], parts["run_p"], env_revoked, policy_revoked,
            base_dir=parts["tmp"],
        )
        check("AUTH-2: revoked key rejected",
              r2["reason"] == REASON_NOT_AUTHORISED, str(r2))


def test_malformed_signature():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], signature_hex="zz-not-hex")
        r = verify_with(parts, envelope=env)
        check("SIG-1: non-hex signature rejected",
              r["reason"] == REASON_SIGNATURE_INVALID, str(r))

        env2 = dict(parts["envelope"], signature_hex="ab" * 63)
        r2 = verify_with(parts, envelope=env2)
        check("SIG-2: wrong-length signature rejected",
              r2["reason"] == REASON_SIGNATURE_INVALID, str(r2))

        env3 = dict(parts["envelope"])
        env3["signature_hex"] = ("ab" * 64)  # valid-looking but forged
        r3 = verify_with(parts, envelope=env3)
        check("SIG-3: forged signature rejected",
              r3["reason"] == REASON_SIGNATURE_INVALID, str(r3))


def test_unknown_envelope_field():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], stray_field="nope")
        r = verify_with(parts, envelope=env)
        check("FIELD-1: unknown envelope field rejected",
              r["reason"] == REASON_UNKNOWN_FIELD, str(r))


def test_old_publisher_manifest_version():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"], schema_version="publisher-manifest.v0")
        r = verify_with(parts, envelope=env)
        check("VER-1: old publisher-manifest version rejected",
              r["reason"] == REASON_UNSUPPORTED_VERSION, str(r))


def test_policy_mismatch():
    with tempfile.TemporaryDirectory() as td:
        parts = make_bundle(pathlib.Path(td))
        env = dict(parts["envelope"])
        env["policy"] = {"id": _POLICY_ID, "hash": "0" * 64}
        r = verify_with(parts, envelope=env)
        check("POL-1: envelope policy hash mismatch rejected",
              r["reason"] == REASON_POLICY_MISMATCH, str(r))

        env2 = dict(parts["envelope"])
        env2["policy"] = {"id": "other-policy", "hash": policy_hash(parts["policy"])}
        r2 = verify_with(parts, envelope=env2)
        check("POL-2: envelope policy id mismatch rejected",
              r2["reason"] == REASON_POLICY_MISMATCH, str(r2))


def _run_validate(cwd, args, env_extra=None):
    env = {**os.environ, "PYTHONPATH": "scripts/evidence:scripts/validate"}
    if env_extra:
        env.update(env_extra)
    script = str(_REPO_ROOT / "scripts/validate/validate_artifact_registry.py")
    return subprocess.run(
        [sys.executable, script, *args],
        capture_output=True, text=True, cwd=cwd, env=env,
    )


def make_abs_bundle(tmp: pathlib.Path):
    """Reproducible bundle using absolute artifact paths + repo-root cwd.

    validate_artifact_registry resolves artifact paths relative to cwd and finds
    config/evidence.json relative to cwd.  Running it from the repo root with
    absolute artifact/run-manifest paths keeps those two consistent.
    """
    parts = make_bundle(tmp)
    reg = dict(parts["registry"])
    reg["artifacts"] = [
        {**a, "path": str(tmp / pathlib.Path(a["path"]))} for a in reg["artifacts"]
    ]
    reg["run_manifest"] = dict(reg["run_manifest"], path=str(parts["run_p"]))
    (tmp / "test-artifacts.json").write_text(json.dumps(reg))
    env_abs = sign_publisher_commitment(
        reg, parts["run_p"], parts["policy"], parts["seed_hex"],
        _PUBLISHER_ID, _KEY_ID,
    )
    (tmp / "publication.json").write_text(json.dumps(env_abs))
    (tmp / "publisher-policy.json").write_text(json.dumps(parts["policy"]))
    return {**parts, "registry": reg, "envelope": env_abs}


def _validate_args(tmp: pathlib.Path) -> list[str]:
    return [
        "--registry", str(tmp / "test-artifacts.json"),
        "--run-manifest", str(tmp / "test-run.json"),
        "--summary", str(tmp / "test-summary.json"),
        "--claimable", str(tmp / "claimable-classification.json"),
        "--publisher-manifest", str(tmp / "publication.json"),
        "--publisher-policy", str(tmp / "publisher-policy.json"),
    ]


def test_integration_gate_accepts_valid():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        make_abs_bundle(tmp)
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("INT-1: gate accepts valid signed bundle (exit 0)",
              res.returncode == 0, f"exit={res.returncode} out={res.stdout} err={res.stderr}")
        check("INT-2: publisher PASS line present",
              "publisher commitment signed by authorised publisher" in res.stdout,
              res.stdout)


def test_integration_gate_rejects_tamper():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        # Tamper with a field the content-integrity stage cannot detect
        # (importance is not cross-checked there), so only the publisher
        # commitment gate can reject the bundle.
        reg = dict(parts["registry"])
        reg["artifacts"] = [
            {**a, "importance": "DIAGNOSTIC"} if a["id"] == "test-summary" else a
            for a in reg["artifacts"]
        ]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("INT-3: gate rejects tampered bundle (exit != 0)",
              res.returncode != 0, f"exit={res.returncode}")
        check("INT-4: rejection reason names publisher",
              "publisher commitment rejected" in res.stdout, res.stdout)


def test_integration_requires_both_publisher_args():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        make_abs_bundle(tmp)
        args = _validate_args(tmp)
        # drop the --publisher-policy flag and its value pair
        del args[args.index("--publisher-policy"):args.index("--publisher-policy") + 2]
        res = _run_validate(_REPO_ROOT, args)
        check("INT-5: publisher gate requires both args",
              res.returncode != 0 and "requires BOTH" in res.stdout,
              res.stdout)


def test_integration_valid_signature_still_rejected_by_stage2():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        # A correctly-signed but incompatible registry must be rejected by the
        # preceding content-integrity checks before the publisher gate runs.
        reg = dict(parts["registry"])
        reg["artifacts"] = [a for a in reg["artifacts"] if a["id"] != "claimable-classification"]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("INT-6: incompatible registry rejected by stage 2 before publisher",
              res.returncode != 0 and "required artifact id missing" in res.stdout,
              res.stdout)


# ── P1: canonical results artifact ────────────────────────────────────────────

def test_missing_results_artifact_rejected():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        reg = dict(parts["registry"])
        reg["artifacts"] = [a for a in reg["artifacts"] if a["id"] != "results-artifact"]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-1: missing results artifact rejected by required chain",
              res.returncode != 0 and "required artifact id missing from registry: results-artifact" in res.stdout,
              res.stdout)


def test_duplicate_results_artifact_rejected():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        first = next(a for a in parts["registry"]["artifacts"] if a["id"] == "results-artifact")
        dup2 = dict(first, path=str(tmp / "test-run.json"), sha256=sha256_file(tmp / "test-run.json"))
        reg = dict(parts["registry"], artifacts=parts["registry"]["artifacts"] + [dup2])
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-2: duplicate results artifact rejected",
              res.returncode != 0 and "duplicate results artifact entries" in res.stdout,
              res.stdout)


def test_results_artifact_wrong_kind_schema():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        reg = dict(parts["registry"])
        reg["artifacts"] = [
            {**a, "kind": "scenario-result", "schema_version": "scenario-result.v1"}
            if a["id"] == "results-artifact" else a
            for a in reg["artifacts"]
        ]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-3: wrong results artifact kind/schema rejected",
              res.returncode != 0 and ("results artifact kind must be" in res.stdout
                                       or "results artifact schema_version must be" in res.stdout),
              res.stdout)


def test_results_artifact_other_run_id():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        reg = dict(parts["registry"])
        reg["artifacts"] = [
            {**a, "extensions": {"run_id": "other-run"}}
            if a["id"] == "results-artifact" else a
            for a in reg["artifacts"]
        ]
        (tmp / "test-artifacts.json").write_text(json.dumps(reg))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-4: results artifact bound to another run_id rejected",
              res.returncode != 0 and "results artifact run binding mismatch" in res.stdout,
              res.stdout)


def test_results_present_but_not_publisher_bound():
    """A registry that adds a results artifact AFTER the envelope was signed
    must be rejected: the publisher commitment does not cover it."""
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        reg_no_results = dict(parts["registry"])
        reg_no_results["artifacts"] = [a for a in reg_no_results["artifacts"]
                                       if a["id"] != "results-artifact"]
        env_no_results = sign_publisher_commitment(
            reg_no_results, parts["run_p"], parts["policy"], parts["seed_hex"],
            _PUBLISHER_ID, _KEY_ID,
        )
        (tmp / "publication.json").write_text(json.dumps(env_no_results))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-5: results present but not publisher-bound rejected",
              res.returncode != 0 and "publisher commitment rejected" in res.stdout,
              res.stdout)


def test_results_changed_after_publication():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_bundle(tmp)
        (tmp / "results-artifact.json").write_text(
            json.dumps({"schema_version": "results-artifact.v1", "run_id": "run-test-001",
                        "results": {"outcome": "fail"}}))
        r = verify_with(parts)
        check("RA-6: results file changed after publication rejected",
              r["reason"] == REASON_COMMITMENT_MISMATCH, str(r))


def test_results_registry_entry_points_to_altered_bytes():
    """The registry declares an old sha256 but the file on disk changed: stage 2
    must reject the on-disk hash mismatch even before the publisher gate."""
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_abs_bundle(tmp)
        (tmp / "results-artifact.json").write_text(
            json.dumps({"schema_version": "results-artifact.v1", "run_id": "run-test-001",
                        "results": {"outcome": "tampered"}}))
        res = _run_validate(_REPO_ROOT, _validate_args(tmp))
        check("RA-7: registry entry pointing to altered bytes rejected at stage 2",
              res.returncode != 0 and
              ("results artifact on-disk hash mismatch" in res.stdout
               or "sha256 mismatch for artifact id=results-artifact" in res.stdout),
              res.stdout)


def test_acceptance_report_composition():
    from acceptance_report import acceptance_report, STAGES
    ok = {"valid?": True, "reason": "ok", "details": {}}
    report = acceptance_report({s: ok for s in STAGES})
    check("AR-1: all-stages-ok composed report accepted",
          report["accepted?"] is True and all(report[s]["valid?"] for s in STAGES),
          str(report))
    bad = acceptance_report({s: ok for s in STAGES if s != "publisher-commitment"})
    check("AR-2: missing stage is fail-closed",
          bad["accepted?"] is False and bad["publisher-commitment"]["reason"] == "stage-missing",
          str(bad))
    bad2 = acceptance_report({**{s: ok for s in STAGES},
                              "content-integrity": {"valid?": False, "reason": "content-hash-mismatch"}})
    check("AR-3: one failing stage rejects the report",
          bad2["accepted?"] is False and bad2["content-integrity"]["reason"] == "content-hash-mismatch",
          str(bad2))


def test_report_json_integration():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        make_abs_bundle(tmp)
        report_path = tmp / "acceptance-report.json"
        args = _validate_args(tmp) + ["--report-json", str(report_path)]
        res = _run_validate(_REPO_ROOT, args)
        check("AR-4: validator exits 0 with --report-json",
              res.returncode == 0, f"exit={res.returncode} out={res.stdout}")
        if report_path.exists():
            report = json.loads(report_path.read_text())
            check("AR-5: report accepted? true", report.get("accepted?") is True, str(report))
            check("AR-6: publisher-commitment stage valid",
                  report["publisher-commitment"]["valid?"] is True, str(report))
            check("AR-7: all five stages present",
                  all(s in report for s in ("content-integrity", "registry-membership",
                                            "required-chain", "publisher-commitment",
                                            "file-integrity")), str(list(report.keys())))
        else:
            check("AR-8: report file written", False, res.stdout)


def test_cli_sign_and_verify_roundtrip():
    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        parts = make_bundle(tmp)
        (tmp / "publisher-policy.json").write_text(json.dumps(parts["policy"]))
        env = {**os.environ, "PYTHONPATH": "scripts/evidence:scripts/validate"}
        sig = subprocess.run(
            [sys.executable, "scripts/evidence/sign_publisher_commitment.py",
             "--registry", str(tmp / "test-artifacts.json"),
             "--run-manifest", str(tmp / "test-run.json"),
             "--policy", str(tmp / "publisher-policy.json"),
             "--private-key-seed-hex", parts["seed_hex"],
             "--publisher-id", _PUBLISHER_ID,
             "--key-id", _KEY_ID,
             "--output", str(tmp / "cli-publication.json")],
            capture_output=True, text=True, cwd=_REPO_ROOT, env=env,
        )
        check("CLI-1: signer CLI exits 0", sig.returncode == 0, sig.stderr)
        ver = subprocess.run(
            [sys.executable, "scripts/validate/verify_publisher_commitment.py",
             "--registry", str(tmp / "test-artifacts.json"),
             "--run-manifest", str(tmp / "test-run.json"),
             "--publisher-manifest", str(tmp / "cli-publication.json"),
             "--publisher-policy", str(tmp / "publisher-policy.json")],
            capture_output=True, text=True, cwd=_REPO_ROOT, env=env,
        )
        check("CLI-2: verifier CLI exits 0", ver.returncode == 0,
              f"exit={ver.returncode} out={ver.stdout} err={ver.stderr}")
        check("CLI-3: verifier prints PASS",
              "[publisher] PASS" in ver.stdout, ver.stdout)


# ── run ───────────────────────────────────────────────────────────────────────

def main():
    print("=== publisher commitment gate tests ===\n")
    print("--- golden fixture ---")
    test_golden_fixture()
    print("\n--- validity ---")
    test_valid_signature_over_unchanged_registry()
    print("\n--- adversarial: commitment binding ---")
    test_artifact_modified_after_signing()
    test_artifact_path_changed_same_hash()
    test_artifact_added_or_removed()
    test_artifact_reordered_is_canonical()
    test_importance_changed()
    test_run_manifest_changed()
    test_run_id_substituted()
    print("\n--- adversarial: signature/key/policy ---")
    test_signature_copied_from_another_run()
    test_digest_copied_into_envelope_not_matching()
    test_unknown_algorithm()
    test_unknown_key()
    test_unauthorised_or_revoked_key()
    test_malformed_signature()
    test_unknown_envelope_field()
    test_old_publisher_manifest_version()
    test_policy_mismatch()
    print("\n--- acceptance-bar wiring ---")
    test_integration_gate_accepts_valid()
    test_integration_gate_rejects_tamper()
    test_integration_requires_both_publisher_args()
    test_integration_valid_signature_still_rejected_by_stage2()
    print("\n--- CLI round-trip ---")
    test_cli_sign_and_verify_roundtrip()

    print("\n--- P1: canonical results artifact ---")
    test_missing_results_artifact_rejected()
    test_duplicate_results_artifact_rejected()
    test_results_artifact_wrong_kind_schema()
    test_results_artifact_other_run_id()
    test_results_present_but_not_publisher_bound()
    test_results_changed_after_publication()
    test_results_registry_entry_points_to_altered_bytes()

    print("\n--- P2: composed acceptance report ---")
    test_acceptance_report_composition()
    test_report_json_integration()

    print(f"\n=== {_PASS} passed, {_FAIL} failed ===")
    return 1 if _FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
