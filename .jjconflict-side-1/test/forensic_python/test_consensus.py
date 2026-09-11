"""Unit tests for scripts/forensic/consensus.py.

Tests the Phase 1 local consensus coordinator: result submission collection,
per-field agreement computation, verdict determination, certificate and
disagreement artifact building.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import consensus


def _make_bundle_with_hash(tmp_path: Path, bundle_hash: str,
                           overview_hash: str,
                           status: str = "pass",
                           registry_snapshot: dict | None = None) -> Path:
    """Create a minimal run bundle with a specific bundle hash and overview hash.
    
    Writes in the Clojure nested format that _build_summary expects
    (e.g. execution.summary.status as {"execution": {"summary": {"status": ...}}}).
    """
    import uuid
    run_dir = tmp_path / f"bundle-{uuid.uuid4().hex[:8]}"
    (run_dir / "evidence-dag").mkdir(parents=True)
    (run_dir / "claims").mkdir()
    (run_dir / "attestations").mkdir()
    (run_dir / "anchors").mkdir()
    # Write minimal required files
    (run_dir / "preflight-report.json").write_text('{"preflight/schema-version":"preflight.v1","preflight/status":"pass"}')
    (run_dir / "source-snapshot.json").write_text('{}')
    (run_dir / "environment.json").write_text('{}')
    (run_dir / "input-manifest.json").write_text('{}')
    (run_dir / "run-overview.json").write_text('{"run-id":"test","status":"pass","exit-code":0}')
    (run_dir / "results-summary.json").write_text('{"results/status":"pass","results/suite-key":"test"}')
    # Anchor
    anchor = {"anchor/schema-version": "anchor-cursor.v1", "anchor/type": "local-proof",
              "anchor/target": f"file://{run_dir}", "anchor/timestamp": "2026-01-01T00:00:00Z"}
    (run_dir / "anchors" / "anchor-cursor.json").write_text(json.dumps(anchor))
    
    # Write clojure-bundle-root.json in nested Clojure format
    clj_data = {
        "bundle": {
            "hash": bundle_hash,
            "schema-version": "bundle-root.v1",
        },
        "overview": {
            "hash": overview_hash,
        },
        "execution": {
            "summary": {
                "status": status,
                "totals": {
                    "total": 125,
                    "passed": 125 if status == "pass" else 120,
                    "failed": 0 if status == "pass" else 5,
                    "expected-failed": 0,
                    "unexpected-failed": 0 if status == "pass" else 5,
                },
            },
        },
        "source": {
            "tree-hash": "deadbeef",
            "tree-hash-algorithm": "sha256sum",
        },
        "run": {"exit-code": 0 if status == "pass" else 1},
    }
    if registry_snapshot:
        clj_data["registry"] = {"snapshot": registry_snapshot}
    clj_path = run_dir / "clojure-bundle-root.json"
    clj_path.write_text(json.dumps(clj_data, indent=2))
    return run_dir


def _submission_from_bundle(run_dir: Path, runner_id: str = "runner/test") -> dict:
    """Create a runner-message submission from a bundle directory."""
    bp = consensus._find_bundle_root(run_dir)
    bundle = consensus.load_bundle(bp)
    summary = consensus._build_summary(bundle) if bundle else {}
    return {
        "runner-message/schema-version": "runner-message.v1",
        "runner-message/type": "result-submission",
        "runner-message/timestamp": "2026-01-01T00:00:00Z",
        "runner-message/runner-id": runner_id,
        "runner-message/run-id": run_dir.name,
        "runner-message/status": "pass",
        "runner-message/exit-code": 0,
        "runner-message/summary": summary,
        "runner-message/bundle-path": str(run_dir),
    }


# ── _build_summary ─────────────────────────────────────────────────────────


class TestBuildSummary:
    def test_extracts_standard_fields(self, tmp_path: Path):
        """_build_summary extracts all standard comparison fields."""
        run_dir = _make_bundle_with_hash(tmp_path, "abc", "def")
        bp = consensus._find_bundle_root(run_dir)
        bundle = consensus.load_bundle(bp)
        summary = consensus._build_summary(bundle)
        for f in consensus.CONSENSUS_FIELDS:
            assert f in summary, f"Missing field: {f}"

    def test_extracts_registry_snapshot(self, tmp_path: Path):
        """_build_summary extracts registry/snapshot/* keys."""
        snap = {"registry-hash": "abc123", "scenario-hash": "def456"}
        run_dir = _make_bundle_with_hash(tmp_path, "abc", "def",
                                          registry_snapshot=snap)
        bp = consensus._find_bundle_root(run_dir)
        bundle = consensus.load_bundle(bp)
        summary = consensus._build_summary(bundle)
        assert summary.get("registry/snapshot/registry-hash") == "abc123"
        assert summary.get("registry/snapshot/scenario-hash") == "def456"

    def test_missing_bundle_returns_empty(self):
        """No bundle → empty summary."""
        summary = consensus._build_summary({})
        assert summary == {}


# ── collect_submissions ────────────────────────────────────────────────────


class TestCollectSubmissions:
    def test_collects_single_submission(self, tmp_path: Path):
        """Single run dir → returns one submission."""
        run_dir = _make_bundle_with_hash(tmp_path, "abc", "def")
        subs = consensus.collect_submissions([run_dir])
        assert len(subs) == 1
        assert subs[0]["runner-message/type"] == "result-submission"
        assert subs[0]["runner-message/status"] == "pass"

    def test_collects_multiple_submissions(self, tmp_path: Path):
        """Multiple run dirs → returns multiple submissions."""
        dirs = []
        for i in range(3):
            d = _make_bundle_with_hash(tmp_path, f"hash-{i}", f"ov-{i}")
            dirs.append(d)
        subs = consensus.collect_submissions(dirs)
        assert len(subs) == 3
        for i, s in enumerate(subs):
            assert s["runner-message/run-id"] == dirs[i].name

    def test_collects_summary(self, tmp_path: Path):
        """Submission includes the built summary."""
        run_dir = _make_bundle_with_hash(tmp_path, "abc123", "def456")
        subs = consensus.collect_submissions([run_dir])
        summary = subs[0].get("runner-message/summary", {})
        assert summary.get("bundle/hash") == "abc123"
        assert summary.get("overview/hash") == "def456"

    def test_missing_run_dir_returns_error(self, tmp_path: Path):
        """Non-existent run directory produces a submission without summary."""
        fake_dir = tmp_path / "nonexistent"
        fake_dir.mkdir()
        subs = consensus.collect_submissions([fake_dir])
        assert len(subs) == 1
        assert subs[0]["runner-message/status"] == "fail"
        assert subs[0].get("runner-message/summary", {}) == {}


# ── compute_agreement ──────────────────────────────────────────────────────


class TestComputeAgreement:
    def test_all_agree_pass(self):
        """All runners agree on all fields → confirmed."""
        subs = [
            _make_sub("abc", "def"),
            _make_sub("abc", "def"),
            _make_sub("abc", "def"),
        ]
        agreement = consensus.compute_agreement(subs, threshold=2)
        assert all(a["status"] == "confirmed" for a in agreement)

    def test_one_dissenter_diverges(self):
        """One runner disagrees on bundle hash → diverged if threshold=3 (needs 3/3)."""
        subs = [
            _make_sub("abc", "def"),
            _make_sub("abc", "def"),
            _make_sub("xxx", "def"),
        ]
        agreement = consensus.compute_agreement(subs, threshold=3)
        bundle_field = next(a for a in agreement if a["field"] == "bundle/hash")
        assert bundle_field["status"] == "diverged"
        # overview/hash has all 3 matching → confirmed even at threshold 3
        ov_field = next(a for a in agreement if a["field"] == "overview/hash")
        assert ov_field["status"] == "confirmed"

    def test_insufficient_runners_for_threshold(self):
        """Fewer than threshold runners → diverged."""
        subs = [_make_sub("abc", "def"), _make_sub("abc", "def")]
        agreement = consensus.compute_agreement(subs, threshold=3)
        assert all(a["status"] == "diverged" for a in agreement)

    def test_empty_submissions(self):
        """No submissions → empty agreement."""
        agreement = consensus.compute_agreement([], threshold=2)
        assert agreement == []


# ── compute_verdict ────────────────────────────────────────────────────────


class TestComputeVerdict:
    def test_all_confirmed_no_failures(self):
        """All confirmed, no failures → confirmed."""
        agreement = [{"status": "confirmed"}, {"status": "confirmed"}]
        verdict = consensus.compute_verdict(agreement, [0, 0])
        assert verdict == "confirmed"

    def test_all_confirmed_with_failures(self):
        """All confirmed but some runs failed → confirmed-with-failures."""
        agreement = [{"status": "confirmed"}, {"status": "confirmed"}]
        verdict = consensus.compute_verdict(agreement, [0, 1])
        assert verdict == "confirmed-with-failures"

    def test_diverged_fields(self):
        """Diverged fields → diverged."""
        agreement = [{"status": "confirmed"}, {"status": "diverged"}]
        verdict = consensus.compute_verdict(agreement, [0, 0])
        assert verdict == "diverged"

    def test_inconclusive(self):
        """No agreement data → inconclusive."""
        verdict = consensus.compute_verdict([], [0, 0])
        assert verdict == "inconclusive"


# ── build_certificate ──────────────────────────────────────────────────────


class TestBuildCertificate:
    def test_certificate_has_self_hash(self, tmp_path: Path):
        """Certificate contains a self-referential hash."""
        run_dir = _make_bundle_with_hash(tmp_path, "abc", "def")
        subs = consensus.collect_submissions([run_dir, run_dir, run_dir])
        agreement = consensus.compute_agreement(subs, threshold=2)
        verdict = "confirmed"
        cert = consensus.build_certificate("round-test", verdict, agreement,
                                            subs, threshold=2)
        assert cert.get("consensus-certificate/hash") is not None
        assert len(cert["consensus-certificate/hash"]) == 64

    def test_certificate_records_agreed_hash(self, tmp_path: Path):
        """Certificate contains the agreed overview hash."""
        run_dir = _make_bundle_with_hash(tmp_path, "abc", "def123")
        subs = consensus.collect_submissions([run_dir, run_dir])
        agreement = consensus.compute_agreement(subs, threshold=2)
        cert = consensus.build_certificate("round-test", "confirmed",
                                            agreement, subs, threshold=2)
        assert cert["consensus-certificate/agreed-hash"] == "def123"

    def test_certificate_participants(self, tmp_path: Path):
        """Certificate lists all participants with agreement status."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "abc", "def")
        subs = consensus.collect_submissions([d1, d2])
        agreement = consensus.compute_agreement(subs, threshold=2)
        cert = consensus.build_certificate("round-test", "confirmed",
                                            agreement, subs, threshold=2)
        participants = cert.get("consensus-certificate/participant-list", [])
        assert len(participants) == 2
        assert all(p.get("agree") for p in participants)


# ── build_disagreement ─────────────────────────────────────────────────────


class TestBuildDisagreement:
    def test_disagreement_has_self_hash(self, tmp_path: Path):
        """Disagreement report contains a self-referential hash."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "xxx", "def")
        subs = consensus.collect_submissions([d1, d2])
        agreement = consensus.compute_agreement(subs, threshold=2)
        report = consensus.build_disagreement("round-test", "diverged",
                                               agreement, subs)
        assert report.get("disagreement/hash") is not None

    def test_disagreement_lists_divergent_fields(self, tmp_path: Path):
        """Report lists which fields diverged."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "xxx", "yyy")
        subs = consensus.collect_submissions([d1, d2])
        agreement = consensus.compute_agreement(subs, threshold=2)
        report = consensus.build_disagreement("round-test", "diverged",
                                               agreement, subs)
        assert report["disagreement/summary"]["fields-diverged"] >= 2

    def test_disagreement_per_runner_divergence(self, tmp_path: Path):
        """Report shows which runner diverged on which fields."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "xxx", "def")
        subs = consensus.collect_submissions([d1, d2])
        subs[0]["runner-message/runner-id"] = "runner/a"
        subs[1]["runner-message/runner-id"] = "runner/b"
        agreement = consensus.compute_agreement(subs, threshold=2)
        report = consensus.build_disagreement("round-test", "diverged",
                                               agreement, subs)
        runners = report["disagreement/runners"]
        # Runner a agreed on overview/hash, runner b might differ on bundle
        assert any(r for r in runners if r["runner-id"] == "runner/a")
        assert any(r for r in runners if r["runner-id"] == "runner/b")


# ── Integration ─────────────────────────────────────────────────────────────


class TestConsensusIntegration:
    def test_three_agree_round(self, tmp_path: Path):
        """Three identical bundles → confirmed consensus."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d3 = _make_bundle_with_hash(tmp_path, "abc", "def")
        subs = consensus.collect_submissions([d1, d2, d3])
        assert len(subs) == 3
        agreement = consensus.compute_agreement(subs, threshold=2)
        verdict = consensus.compute_verdict(agreement,
                                            [s["runner-message/exit-code"] for s in subs])
        assert verdict == "confirmed"
        cert = consensus.build_certificate("round-test", verdict, agreement,
                                            subs, threshold=2)
        assert cert["consensus-certificate/status"] == "confirmed"
        assert cert["consensus-certificate/agreement-count"] == 3

    def test_two_agree_one_differs(self, tmp_path: Path):
        """Two agree, one differs. With threshold=3 (needs 3/3), bundle hash diverges."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d3 = _make_bundle_with_hash(tmp_path, "xxx", "def")
        subs = consensus.collect_submissions([d1, d2, d3])
        agreement = consensus.compute_agreement(subs, threshold=3)
        bundle_field = next(a for a in agreement if a["field"] == "bundle/hash")
        assert bundle_field["status"] == "diverged"

    def test_empty_run_list(self, tmp_path: Path):
        """No run dirs → inconclusive."""
        subs = consensus.collect_submissions([])
        assert subs == []

    def test_full_run_consensus_from_dirs(self, tmp_path: Path):
        """run_consensus with --from-dirs produces correct artifacts."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d3 = _make_bundle_with_hash(tmp_path, "abc", "def")
        manifest = consensus.run_consensus(
            run_dirs=[d1, d2, d3], threshold=2,
            output_dir=tmp_path / "consensus-out")
        assert manifest["consensus/status"] == "confirmed"
        assert manifest["consensus/submission-count"] == 3
        # Check evidence node was written
        ev_path = tmp_path / "consensus-out" / "consensus" / "node-*.json"
        assert len(list(tmp_path.rglob("node-*.json"))) >= 1

    def test_full_run_consensus_diverged(self, tmp_path: Path):
        """run_consensus with diverged bundles produces disagreement report."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "xxx", "yyy")
        manifest = consensus.run_consensus(
            run_dirs=[d1, d2], threshold=2,
            output_dir=tmp_path / "consensus-div")
        assert manifest["consensus/status"] == "diverged"
        # Disagreement report should exist
        dis_paths = list((tmp_path / "consensus-div").rglob("disagreement-report.json"))
        assert len(dis_paths) >= 1

    def test_evidence_node_content(self, tmp_path: Path):
        """Evidence node has correct execution-id and result status."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        manifest = consensus.run_consensus(
            run_dirs=[d1, d1, d1], threshold=2,
            output_dir=tmp_path / "consensus-ev")
        # Find the evidence node
        for p in (tmp_path / "consensus-ev").rglob("node-*.json"):
            node = json.loads(p.read_text())
            assert node.get("execution", {}).get("execution-id") == "execution/consensus"
            assert node.get("result", {}).get("status") == "pass"
            break

    def test_certificate_no_absolute_paths(self, tmp_path: Path):
        """Stable consensus certificate fields contain no absolute paths."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        manifest = consensus.run_consensus(
            run_dirs=[d1, d1], threshold=2,
            output_dir=tmp_path / "consensus-nopath")
        # Check cert for absolute paths
        for p in (tmp_path / "consensus-nopath").rglob("consensus-certificate.json"):
            cert = json.loads(p.read_text())
            cert_str = json.dumps(cert)
            # participant-list entries should not have /tmp/ paths in stable fields
            for participant in cert.get("consensus-certificate/participant-list", []):
                for val in participant.values():
                    if isinstance(val, str) and val.startswith("/tmp/"):
                        # Only bundle-path can have abs paths, not hash fields
                        assert False, f"Absolute path in participant field: {val}"
            break

    def test_run_consensus_from_dirs_respects_threshold(self, tmp_path: Path):
        """run_consensus --from-dirs with threshold higher than participants → diverged."""
        d1 = _make_bundle_with_hash(tmp_path, "abc", "def")
        d2 = _make_bundle_with_hash(tmp_path, "abc", "def")
        manifest = consensus.run_consensus(
            run_dirs=[d1, d2], threshold=3,
            output_dir=tmp_path / "consensus-thresh")
        assert manifest["consensus/status"] == "diverged"


# ── Helper ─────────────────────────────────────────────────────────────────


def _make_sub(bundle_hash: str, overview_hash: str,
              status: str = "pass") -> dict:
    """Create a minimal consensus submission for testing.
    
    Uses flat keys (what _build_summary produces after flattening nested Clojure
    bundle root into 'bundle/hash', 'overview/hash', etc.).
    """
    summary = {
        "bundle/hash": bundle_hash,
        "overview/hash": overview_hash,
        "execution/summary/status": status,
        "execution/summary/totals/total": 125,
        "execution/summary/totals/passed": 125,
        "execution/summary/totals/failed": 0,
        "execution/summary/totals/expected-failed": 0,
        "execution/summary/totals/unexpected-failed": 0,
        "source/tree-hash": "deadbeef",
        "source/tree-hash-algorithm": "sha256sum",
    }
    return {
        "runner-message/schema-version": "runner-message.v1",
        "runner-message/type": "result-submission",
        "runner-message/timestamp": "2026-01-01T00:00:00Z",
        "runner-message/runner-id": "runner/test",
        "runner-message/run-id": "test-run",
        "runner-message/status": status,
        "runner-message/exit-code": 0 if status == "pass" else 1,
        "runner-message/summary": summary,
    }
