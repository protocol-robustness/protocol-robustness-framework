from __future__ import annotations

from pathlib import Path

import sys

_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import reproduce
from forensic import source_hash as sut


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def test_source_tree_hash_detects_same_size_edits(tmp_path: Path):
    _write(tmp_path / "src" / "example.clj", "(def value 1)\n")
    before, _ = sut.compute_source_tree_hash(tmp_path, ["src"])
    _write(tmp_path / "src" / "example.clj", "(def value 2)\n")
    after, _ = sut.compute_source_tree_hash(tmp_path, ["src"])
    assert before != after


def test_source_tree_hash_respects_roots(tmp_path: Path):
    _write(tmp_path / "src" / "included.clj", "(def included 1)\n")
    _write(tmp_path / "notes" / "ignored.clj", "(def ignored 1)\n")
    before, included_roots = sut.compute_source_tree_hash(tmp_path, ["src"])
    _write(tmp_path / "notes" / "ignored.clj", "(def ignored 2)\n")
    after, _ = sut.compute_source_tree_hash(tmp_path, ["src"])
    assert before == after
    assert included_roots == ["src"]


def test_source_tree_hash_includes_files_beyond_one_thousand(tmp_path: Path):
    for idx in range(1001):
        _write(tmp_path / "src" / f"f{idx:04d}.clj", f"(def v{idx} 1)\n")
    before, _ = sut.compute_source_tree_hash(tmp_path, ["src"])
    _write(tmp_path / "src" / "f1000.clj", "(def v1000 2)\n")
    after, _ = sut.compute_source_tree_hash(tmp_path, ["src"])
    assert before != after


def test_reproduce_classifies_mismatched_algorithm():
    status = reproduce.classify_source_precheck(
        "abc123",
        "source-tree-hash.v0.shell-sha256sum",
        "abc123",
        sut.SOURCE_TREE_HASH_ALGORITHM,
    )
    assert status["reason"] == "algorithm-mismatch"

def test_reproduce_classifies_missing_algorithm():
    status = reproduce.classify_source_precheck(
        "abc123",
        None,
        "abc123",
        sut.SOURCE_TREE_HASH_ALGORITHM,
    )
    assert status["reason"] == "missing/algorithm-field"
