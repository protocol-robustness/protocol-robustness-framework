"""Unit tests for scripts/forensic/mailbox.py.

Tests the Phase 2 shared artifact mailbox: initialization, content-addressed
object storage, message write/read, and consensus integration.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import mailbox
from test.forensic_python.conftest import make_minimal_bundle


# ── Canonical JSON / Hashing ────────────────────────────────────────────────


class TestCanonicalHashing:
    def test_canonical_json_is_deterministic(self):
        """Same data → same canonical bytes."""
        a = {"z": 1, "a": 2}
        b = {"a": 2, "z": 1}
        assert mailbox.canonical_json_bytes(a) == mailbox.canonical_json_bytes(b)

    def test_sha256_hex_length(self):
        """SHA-256 hex is 64 characters."""
        h = mailbox.sha256_hex(b"test")
        assert len(h) == 64

    def test_self_hash_excludes_keys(self):
        """self_hash strips exclude keys before hashing."""
        data = {"a": 1, "hash": "old", "b": 2}
        h = mailbox.self_hash(data, ["hash"])
        # Hash must be deterministic — same data same hash
        h2 = mailbox.self_hash({"a": 1, "b": 2, "hash": "anything"}, ["hash"])
        assert h == h2


# ── Mailbox Init ─────────────────────────────────────────────────────────────


class TestMailboxInit:
    def test_init_creates_directories(self, tmp_path: Path):
        """init_mailbox creates the required directory structure."""
        mb = tmp_path / "test-mailbox"
        mailbox.init_mailbox(mb)
        assert (mb / "mailbox.json").exists()
        assert (mb / "runners").is_dir()
        assert (mb / "objects" / "sha256").is_dir()

    def test_init_writes_metadata(self, tmp_path: Path):
        """init_mailbox writes mailbox.json with schema version."""
        mb = tmp_path / "test-mailbox"
        mailbox.init_mailbox(mb)
        meta = json.loads((mb / "mailbox.json").read_text())
        assert meta["mailbox/schema-version"] == "forensic-mailbox.v1"
        assert meta["mailbox/transport"] == "filesystem"

    def test_init_existing_mailbox_raises(self, tmp_path: Path):
        """Initializing an already-initialized mailbox raises."""
        mb = tmp_path / "test-mailbox"
        mailbox.init_mailbox(mb)
        with pytest.raises(FileExistsError):
            mailbox.init_mailbox(mb)

    def test_init_over_empty_dir_succeeds(self, tmp_path: Path):
        """Initializing an empty directory succeeds."""
        mb = tmp_path / "empty-dir"
        mb.mkdir()
        mailbox.init_mailbox(mb)
        assert (mb / "mailbox.json").exists()


# ── Object Store ─────────────────────────────────────────────────────────────


class TestObjectStore:
    def test_write_read_roundtrip(self, tmp_path: Path):
        """Writing and reading an object returns the same data."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        obj = {"hello": "world", "number": 42}
        h = mailbox.write_object(mb, obj)
        assert len(h) == 64
        read = mailbox.read_object(mb, h)
        assert read is not None
        assert read["hello"] == "world"

    def test_write_read_nonexistent(self, tmp_path: Path):
        """Reading a nonexistent hash returns None."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        assert mailbox.read_object(mb, "a" * 64) is None

    def test_object_path_is_deterministic(self, tmp_path: Path):
        """Same content → same hash, same path."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        h1 = mailbox.write_object(mb, {"data": "test"})
        h2 = mailbox.write_object(mb, {"data": "test"})
        assert h1 == h2

    def test_object_has_metadata(self, tmp_path: Path):
        """Stored object includes metadata fields."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        h = mailbox.write_object(mb, {"data": "test"})
        read = mailbox.read_object(mb, h)
        assert read.get("object/schema-version") == mailbox.OBJECT_SCHEMA_VERSION
        assert read.get("object/hash-algorithm") == mailbox.OBJECT_HASH_ALGORITHM
        assert read.get("object/hash") == h


# ── Runner Announcement ──────────────────────────────────────────────────────


class TestRunnerAnnouncement:
    def test_write_announcement_creates_file(self, tmp_path: Path):
        """write_runner_announcement creates a runner.json file."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        msg = mailbox.write_runner_announcement(mb, "runner/test",
                                                 capabilities=["sew"])
        assert msg["runner-message/type"] == "announcement"
        assert (mb / "runners" / "runner/test" / "runner.json").exists()

    def test_announcement_has_hash(self, tmp_path: Path):
        """Announcement message includes a self-referential hash."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        msg = mailbox.write_runner_announcement(mb, "runner/test")
        h = msg.get("runner-message/hash")
        assert h is not None and len(h) == 64


# ── Run Request ──────────────────────────────────────────────────────────────


class TestRunRequest:
    def test_write_run_request_creates_directory(self, tmp_path: Path):
        """write_run_request creates a run directory with request.json."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {"suite": "sew"}, run_id="test-run")
        assert (mb / "runs" / rrh / "request.json").exists()

    def test_run_request_hash_is_consistent(self, tmp_path: Path):
        """Same run_id → same run request hash."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh1 = mailbox.write_run_request(mb, {}, run_id="test")
        rrh2 = mailbox.write_run_request(mb, {}, run_id="test")
        assert rrh1 == rrh2

    def test_run_request_hash_varies_by_id(self, tmp_path: Path):
        """Different run_id → different run request hash."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh1 = mailbox.write_run_request(mb, {}, run_id="run-a")
        rrh2 = mailbox.write_run_request(mb, {}, run_id="run-b")
        assert rrh1 != rrh2


# ── Result Submissions ───────────────────────────────────────────────────────


class TestResultSubmission:
    def test_write_submission_creates_file(self, tmp_path: Path):
        """write_result_submission creates a submission file."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        msg = mailbox.write_result_submission(
            mb, rrh, "runner/a",
            {"bundle/hash": "abc", "overview/hash": "def"},
            exit_code=0)
        assert msg["runner-message/type"] == "result-submission"
        assert (mb / "runs" / rrh / "submissions").exists()

    def test_list_submissions_returns_messages(self, tmp_path: Path):
        """list_result_submissions returns all stored messages."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        mailbox.write_result_submission(mb, rrh, "runner/a", {"h": "a"})
        mailbox.write_result_submission(mb, rrh, "runner/b", {"h": "b"})
        msgs = mailbox.list_result_submissions(mb, rrh)
        assert len(msgs) == 2

    def test_load_submissions_normalizes(self, tmp_path: Path):
        """load_result_submissions returns Phase 1 compatible format."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        mailbox.write_result_submission(
            mb, rrh, "runner/a",
            {"bundle/hash": "abc", "overview/hash": "def"},
            exit_code=0)
        loaded, _warnings = mailbox.load_result_submissions(mb, rrh)
        assert len(loaded) == 1
        assert loaded[0]["runner-message/exit-code"] == 0
        assert loaded[0]["runner-message/summary"]["bundle/hash"] == "abc"

    def test_empty_submissions_returns_empty(self, tmp_path: Path):
        """No submissions → empty list."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        loaded, warnings = mailbox.load_result_submissions(mb, rrh)
        assert loaded == []
        assert warnings == []


# ── Deduplication ─────────────────────────────────────────────────────────────


class TestDeduplication:
    def test_identical_duplicate_silently_deduplicated(self, tmp_path: Path):
        """Same runner, same hash → idempotent (kept once, no warning).
        Two calls produce different hashes due to timestamp differences.
        This test verifies dedup when the same dict is written as a file twice."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="dedup")
        summary = {"bundle/hash": "abc", "overview/hash": "def"}
        m1 = mailbox.write_result_submission(mb, rrh, "runner/a", summary)
        # Write the exact same message dict again to the same directory
        from scripts.forensic.mailbox import _write_message as _wm
        from scripts.forensic.mailbox import _safe_filename as _sf
        _wm(mb, f"runs/{rrh}/submissions",
            f"{_sf(['runner/a', m1['runner-message/hash'][:16]])}.json", m1)
        loaded, warnings = mailbox.load_result_submissions(mb, rrh)
        assert len(loaded) == 1
        # No equivocation warning (same hash)
        assert not any(w.get("warning/code") == "runner-equivocation" for w in warnings)

    def test_different_hash_equivocation_warns(self, tmp_path: Path):
        """Same runner, different hash → equivocation warning."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="equiv")
        mailbox.write_result_submission(mb, rrh, "runner/a",
                                         {"bundle/hash": "abc", "overview/hash": "def"})
        mailbox.write_result_submission(mb, rrh, "runner/a",
                                         {"bundle/hash": "xxx", "overview/hash": "yyy"})
        loaded, warnings = mailbox.load_result_submissions(mb, rrh)
        assert any(w.get("warning/code") == "runner-equivocation" for w in warnings)
        # Only the latest should be accepted
        assert len(loaded) == 1

    def test_different_runners_same_hash_ok(self, tmp_path: Path):
        """Different runners, same hash → normal agreement (no warning)."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="multi-runner")
        summary = {"bundle/hash": "abc", "overview/hash": "def"}
        mailbox.write_result_submission(mb, rrh, "runner/a", summary)
        mailbox.write_result_submission(mb, rrh, "runner/b", summary)
        loaded, warnings = mailbox.load_result_submissions(mb, rrh)
        assert len(loaded) == 2
        # No equivocation warnings (different runners)
        assert not any(w.get("warning/code") == "runner-equivocation" for w in warnings)

    def test_malformed_runner_id_rejected(self, tmp_path: Path):
        """Empty or missing runner-id → rejected with warning."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="malformed")
        mailbox.write_result_submission(mb, rrh, "", {"bundle/hash": "abc"})
        mailbox.write_result_submission(mb, rrh, "runner/b", {"bundle/hash": "def"})
        loaded, warnings = mailbox.load_result_submissions(mb, rrh)
        assert len(loaded) == 1
        assert any(w.get("warning/code") == "malformed-runner-id" for w in warnings)


# ── Error Reports ────────────────────────────────────────────────────────────


class TestErrorReport:
    def test_write_error_creates_file(self, tmp_path: Path):
        """write_error_report creates an error file."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        msg = mailbox.write_error_report(mb, rrh, "runner/a",
                                          "crash", "OOM")
        assert msg["runner-message/type"] == "error-report"
        assert (mb / "runs" / rrh / "errors").exists()


# ── Consensus Writeback ──────────────────────────────────────────────────────


class TestConsensusWriteback:
    def test_write_certificate_to_mailbox(self, tmp_path: Path):
        """write_consensus_outputs stores certificate in the mailbox."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        cert = {"consensus-certificate/schema-version": "consensus-certificate.v1",
                "consensus-certificate/status": "confirmed",
                "consensus-certificate/hash": "abc"}
        mailbox.write_consensus_outputs(mb, rrh, certificate=cert)
        assert (mb / "runs" / rrh / "consensus" / "consensus-certificate.json").exists()

    def test_write_consensus_also_stores_objects(self, tmp_path: Path):
        """write_consensus_outputs also writes content-addressed objects."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        cert = {"consensus-certificate/schema-version": "consensus-certificate.v1",
                "consensus-certificate/status": "confirmed",
                "consensus-certificate/hash": None,
                "consensus-certificate/signature": None}
        mailbox.write_consensus_outputs(mb, rrh, certificate=cert)
        # Object should be stored under objects/sha256/
        obj_dir = mb / "objects" / "sha256"
        assert obj_dir.exists()
        assert any(obj_dir.rglob("*.json"))


# ── Bundle Artifact ──────────────────────────────────────────────────────────


class TestBundleArtifact:
    def test_write_artifact_returns_hash(self, tmp_path: Path):
        """write_bundle_artifact returns a manifest hash."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        bundle_dir = make_minimal_bundle(tmp_path)
        mh = mailbox.write_bundle_artifact(mb, rrh, bundle_dir)
        assert len(mh) == 64


# ── Mailbox Summary ──────────────────────────────────────────────────────────


class TestMailboxSummary:
    def test_summary_counts(self, tmp_path: Path):
        """mailbox_summary returns correct counts."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        mailbox.write_runner_announcement(mb, "runner/a")
        mailbox.write_runner_announcement(mb, "runner/b")
        mailbox.write_run_request(mb, {}, run_id="r1")
        mailbox.write_run_request(mb, {}, run_id="r2")
        s = mailbox.mailbox_summary(mb)
        assert s["mailbox/runner-count"] == 2
        assert s["mailbox/run-count"] == 2


# ── Commitments ──────────────────────────────────────────────────────────────


class TestCommitments:
    def test_write_commitment(self, tmp_path: Path):
        """write_commitment creates a commitment file."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        mailbox.write_commitment(mb, rrh, "runner/a")
        assert (mb / "runs" / rrh / "commitments").exists()
        files = list((mb / "runs" / rrh / "commitments").iterdir())
        assert len(files) == 1


# ── No Absolute Paths in Stable Fields ───────────────────────────────────────


class TestNoAbsolutePaths:
    def test_submission_summary_no_abs_paths(self, tmp_path: Path):
        """Stable summary fields do not contain absolute paths."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="test")
        summary = {"bundle/hash": "abc", "overview/hash": "def"}
        msg = mailbox.write_result_submission(mb, rrh, "runner/a", summary)
        msg_text = json.dumps(msg)
        # Check that absolute paths do not appear in summary
        assert "/tmp/" not in msg_text or "bundle-path" not in msg_text
        # bundle-path is outside the hashed summary, that's fine


# ── CLI Integration ──────────────────────────────────────────────────────────


class TestCLIIntegration:
    def test_init_via_main(self, tmp_path: Path):
        """mailbox.py init CLI works."""
        mb = tmp_path / "cli-mailbox"
        rc = mailbox.cmd_init([str(mb)])
        assert rc == 0
        assert (mb / "mailbox.json").exists()

    def test_publish_run_via_main(self, tmp_path: Path):
        """mailbox.py publish-run CLI works."""
        mb = tmp_path / "cli-mailbox"
        mailbox.init_mailbox(mb)
        req_file = tmp_path / "request.json"
        req_file.write_text('{"suite/key": "sew"}')
        rc = mailbox.cmd_publish_run([str(mb), str(req_file), "--run-id", "test-cli"])
        assert rc == 0
        # Find the run directory (hashed run-id)
        runs_dir = mb / "runs"
        run_dirs = list(runs_dir.iterdir())
        assert len(run_dirs) >= 1


# ── Consensus Integration ─────────────────────────────────────────────────────


class TestConsensusFromMailbox:
    def test_consensus_from_mailbox_three_agree(self, tmp_path: Path):
        """Consensus computed from mailbox submissions works end to end."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="consensus-test")
        summary = {
            "bundle/hash": "abc",
            "overview/hash": "def",
            "execution/summary/status": "pass",
            "execution/summary/totals/total": 125,
            "execution/summary/totals/passed": 125,
            "execution/summary/totals/failed": 0,
            "execution/summary/totals/expected-failed": 0,
            "execution/summary/totals/unexpected-failed": 0,
            "source/tree-hash": "deadbeef",
            "source/tree-hash-algorithm": "sha256sum",
        }
        for i in range(3):
            mailbox.write_result_submission(mb, rrh, f"runner-{i}", summary)
        manifest = consensus.run_consensus(
            mailbox_dir=mb, run_request_hash=rrh,
            output_dir=tmp_path / "output")
        assert manifest["consensus/status"] == "confirmed"
        assert manifest["consensus/submission-count"] == 3

    def test_mailbox_writeback_creates_certificate(self, tmp_path: Path):
        """Consensus from mailbox writes certificate back to mailbox."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="writeback-test")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        for i in range(2):
            mailbox.write_result_submission(mb, rrh, f"r-{i}", summary)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh,
                                output_dir=tmp_path / "output")
        cert_path = mb / "runs" / rrh / "consensus" / "consensus-certificate.json"
        assert cert_path.exists()
        cert = json.loads(cert_path.read_text())
        assert cert.get("consensus-certificate/status") == "confirmed"

    def test_mailbox_diverged_writes_disagreement(self, tmp_path: Path):
        """Mailbox consensus with diverged submissions writes disagreement report."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="div-test")
        s1 = {"bundle/hash": "abc", "overview/hash": "def",
              "execution/summary/status": "pass",
              "execution/summary/totals/total": 125,
              "execution/summary/totals/passed": 125,
              "execution/summary/totals/failed": 0,
              "execution/summary/totals/expected-failed": 0,
              "execution/summary/totals/unexpected-failed": 0,
              "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        s2 = dict(s1)
        s2["bundle/hash"] = "xxx"
        mailbox.write_result_submission(mb, rrh, "r-a", s1)
        mailbox.write_result_submission(mb, rrh, "r-b", s2)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh, threshold=2,
                                output_dir=tmp_path / "output")
        dis_path = mb / "runs" / rrh / "consensus" / "disagreement-report.json"
        assert dis_path.exists()
        dis = json.loads(dis_path.read_text())
        assert dis.get("disagreement/status") == "diverged"
        assert dis["disagreement/summary"]["fields-diverged"] >= 1

    def test_mailbox_evidence_node_written(self, tmp_path: Path):
        """Mailbox consensus writes evidence node."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="ev-test")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        mailbox.write_result_submission(mb, rrh, "r-a", summary)
        mailbox.write_result_submission(mb, rrh, "r-b", summary)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh,
                                output_dir=tmp_path / "output")
        ev_path = mb / "runs" / rrh / "consensus" / "evidence-node.json"
        assert ev_path.exists()
        node = json.loads(ev_path.read_text())
        assert node.get("execution", {}).get("execution-id") == "execution/consensus"

    def test_mailbox_multiple_run_requests(self, tmp_path: Path):
        """Mailbox supports multiple independent run requests."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh1 = mailbox.write_run_request(mb, {}, run_id="run-a")
        rrh2 = mailbox.write_run_request(mb, {}, run_id="run-b")
        assert rrh1 != rrh2
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        mailbox.write_result_submission(mb, rrh1, "r-a", summary)
        mailbox.write_result_submission(mb, rrh2, "r-b", summary)
        assert len(mailbox.list_result_submissions(mb, rrh1)) == 1
        assert len(mailbox.list_result_submissions(mb, rrh2)) == 1

    def test_mailbox_load_after_consensus(self, tmp_path: Path):
        """Mailbox still readable after consensus writeback."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="post-consensus")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        mailbox.write_result_submission(mb, rrh, "r-a", summary)
        mailbox.write_result_submission(mb, rrh, "r-b", summary)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh,
                                output_dir=tmp_path / "output")
        subs = mailbox.list_result_submissions(mb, rrh)
        assert len(subs) == 2


# ── Hash Stability / Path Independence ──────────────────────────────────────


class TestHashStability:
    def test_certificate_hash_independent_of_mailbox_path(self, tmp_path: Path):
        """Same submissions across different mailbox paths → same agreed-hash."""
        from forensic import consensus
        mb1 = mailbox.init_mailbox(tmp_path / "mb-a")
        mb2 = mailbox.init_mailbox(tmp_path / "mb-b" / "nested" / "deep")
        rrh = mailbox.write_run_request(mb1, {}, run_id="stable")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        for i in range(2):
            mailbox.write_result_submission(mb1, rrh, f"r-{i}", summary,
                                             bundle_path="/tmp/mb-a/run")
            mailbox.write_result_submission(mb2, rrh, f"r-{i}", summary,
                                             bundle_path="/var/mb-b/nested/deep/run")
        subs1, _w1 = mailbox.load_result_submissions(mb1, rrh)
        subs2, _w2 = mailbox.load_result_submissions(mb2, rrh)
        ag1 = consensus.compute_agreement(subs1, threshold=2)
        ag2 = consensus.compute_agreement(subs2, threshold=2)
        cert1 = consensus.build_certificate("r", "confirmed", ag1, subs1, 2)
        cert2 = consensus.build_certificate("r", "confirmed", ag2, subs2, 2)
        # Agreed hash must be identical regardless of mailbox path
        assert cert1["consensus-certificate/agreed-hash"] == "def"
        assert cert2["consensus-certificate/agreed-hash"] == "def"
        assert cert1["consensus-certificate/agreed-hash"] == \
               cert2["consensus-certificate/agreed-hash"]

    def test_certificate_stable_hash_with_identical_params(self, tmp_path: Path):
        """Identical submissions → identical agreed hash regardless of mailbox path.
        (Certificate self-hash includes a timestamp and is not reproducible —
        the agreed-hash IS the stable consensus output.)"""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="stable-hash")
        s1 = {"bundle/hash": "abc", "overview/hash": "def",
              "execution/summary/status": "pass",
              "execution/summary/totals/total": 125,
              "execution/summary/totals/passed": 125,
              "execution/summary/totals/failed": 0,
              "execution/summary/totals/expected-failed": 0,
              "execution/summary/totals/unexpected-failed": 0,
              "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        s2 = dict(s1)
        mailbox.write_result_submission(mb, rrh, "a", s1)
        mailbox.write_result_submission(mb, rrh, "b", s2)
        subs, _w = mailbox.load_result_submissions(mb, rrh)
        ag = consensus.compute_agreement(subs, threshold=2)
        c1 = consensus.build_certificate("round-x", "confirmed", ag, subs, 2)
        c2 = consensus.build_certificate("round-x", "confirmed", ag, subs, 2)
        # The agreed-hash is the stable output (overview hash from summary)
        assert c1["consensus-certificate/agreed-hash"] == "def"
        assert c1["consensus-certificate/agreed-hash"] == \
               c2["consensus-certificate/agreed-hash"]
        # The certificate self-hash differs per call (includes timestamp) — that's expected

    def test_object_hash_independent_of_mailbox_path(self, tmp_path: Path):
        """Identical object content → identical hash regardless of mailbox dir."""
        mb1 = mailbox.init_mailbox(tmp_path / "mb-a")
        mb2 = mailbox.init_mailbox(tmp_path / "mb-b")
        content = {"type": "test", "data": [1, 2, 3]}
        h1 = mailbox.write_object(mb1, content)
        h2 = mailbox.write_object(mb2, content)
        assert h1 == h2

    def test_stable_fields_no_absolute_paths_in_cert(self, tmp_path: Path):
        """Certificate stable fields (hashes, status) must not contain /tmp paths."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="no-abs")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        # Include different bundle paths (absolute) in submissions
        mailbox.write_result_submission(mb, rrh, "a", summary,
                                         bundle_path="/tmp/some/run/dir-a")
        mailbox.write_result_submission(mb, rrh, "b", summary,
                                         bundle_path="/var/other/run/dir-b")
        subs, _w = mailbox.load_result_submissions(mb, rrh)
        ag = consensus.compute_agreement(subs, threshold=2)
        cert = consensus.build_certificate("r", "confirmed", ag, subs, 2)
        cert_str = json.dumps(cert)
        # The participant-list runner-id and hashes should not contain abs paths
        for p in cert.get("consensus-certificate/participant-list", []):
            for key, val in p.items():
                if isinstance(val, str) and val.startswith(("/tmp/", "/var/")):
                    assert False, f"Found absolute path in participant {key}={val}"


# ── Mailbox Validation ──────────────────────────────────────────────────────


class TestMailboxValidation:
    def test_validate_empty_mailbox(self, tmp_path: Path):
        """validate_mailbox on empty mailbox checks mailbox.json."""
        mb = mailbox.init_mailbox(tmp_path / "mb")
        checks = mailbox.validate_mailbox(mb)
        assert any(c.get("check/status") == "pass" for c in checks)
        assert not any(c.get("check/status") == "fail" for c in checks)

    def test_validate_missing_mailbox(self, tmp_path: Path):
        """validate_mailbox on missing dir fails."""
        mb = tmp_path / "nonexistent"
        checks = mailbox.validate_mailbox(mb)
        assert any(c.get("check/status") == "fail" for c in checks)

    def test_validate_run_request(self, tmp_path: Path):
        """validate_mailbox with run request hash checks submissions."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="validate-rr")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        mailbox.write_result_submission(mb, rrh, "a", summary)
        checks = mailbox.validate_mailbox(mb, rrh)
        # Should have run-request check, submission checks, consensus info
        assert any("run-request" in c.get("check/key", "") and
                   c.get("check/status") == "pass" for c in checks)
        assert any("submission" in c.get("check/key", "") and
                   c.get("check/status") == "pass" for c in checks)

    def test_validate_after_consensus(self, tmp_path: Path):
        """validate_mailbox after consensus checks certificate and evidence node."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="validate-post")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        for i in range(2):
            mailbox.write_result_submission(mb, rrh, f"r-{i}", summary)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh,
                                output_dir=tmp_path / "cons-out")
        checks = mailbox.validate_mailbox(mb, rrh)
        # Should have certificate hash verification
        assert any("certificate" in c.get("check/key", "") and
                   c.get("check/status") == "pass" for c in checks)
        # Should have evidence node check
        assert any("evidence-node" in c.get("check/key", "") for c in checks)

    def test_validate_certificate_hash_mismatch(self, tmp_path: Path):
        """validate_mailbox detects tampered certificate."""
        from forensic import consensus
        mb = mailbox.init_mailbox(tmp_path / "mb")
        rrh = mailbox.write_run_request(mb, {}, run_id="validate-tamper")
        summary = {"bundle/hash": "abc", "overview/hash": "def",
                    "execution/summary/status": "pass",
                    "execution/summary/totals/total": 125,
                    "execution/summary/totals/passed": 125,
                    "execution/summary/totals/failed": 0,
                    "execution/summary/totals/expected-failed": 0,
                    "execution/summary/totals/unexpected-failed": 0,
                    "source/tree-hash": "x", "source/tree-hash-algorithm": "sha256"}
        for i in range(2):
            mailbox.write_result_submission(mb, rrh, f"r-{i}", summary)
        consensus.run_consensus(mailbox_dir=mb, run_request_hash=rrh,
                                output_dir=tmp_path / "cons-out2")
        # Tamper with the certificate
        cert_path = mb / "runs" / rrh / "consensus" / "consensus-certificate.json"
        cert = json.loads(cert_path.read_text())
        cert["consensus-certificate/status"] = "tampered"
        cert_path.write_text(json.dumps(cert))
        checks = mailbox.validate_mailbox(mb, rrh)
        assert any(c.get("check/status") == "fail" for c in checks)
