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


def test_default_source_hash_includes_extracted_yield_runtime(tmp_path: Path):
    for root in sut.DEFAULT_SOURCE_ROOTS:
        (tmp_path / root).mkdir(parents=True)
    yield_source = tmp_path / "yield_runtime_src" / "resolver_sim" / "yield" / "partial_fill.clj"
    _write(yield_source, "(def value 1)\n")

    before, included_roots = sut.compute_source_tree_hash(tmp_path, None)
    _write(yield_source, "(def value 2)\n")
    after, _ = sut.compute_source_tree_hash(tmp_path, None)

    assert before != after
    assert included_roots == sut.DEFAULT_SOURCE_ROOTS


def test_generated_source_snapshot_preserves_default_roots(tmp_path: Path, monkeypatch):
    from scripts.forensic import run as forensic_run

    for root in ("src", "yield_runtime_src"):
        (tmp_path / root).mkdir(parents=True)
    _write(tmp_path / "yield_runtime_src" / "resolver_sim" / "yield" / "partial_fill.clj",
           "(def value 1)\n")
    monkeypatch.setattr(forensic_run, "PRF_SOURCE_ROOTS", sut.DEFAULT_SOURCE_ROOTS)

    source = forensic_run.snapshot_source(tmp_path, tmp_path / "run")

    assert source["source-hash-roots"] == sut.DEFAULT_SOURCE_ROOTS
    assert source["included-roots"] == ["src", "yield_runtime_src"]


def test_reproduction_report_preserves_declared_roots(tmp_path: Path, monkeypatch):
    (tmp_path / "yield_runtime_src").mkdir()
    monkeypatch.setattr(reproduce, "PRF_SOURCE_ROOTS", sut.DEFAULT_SOURCE_ROOTS)

    _, algorithm, current_roots = reproduce.current_code_hash(tmp_path)

    assert algorithm == sut.SOURCE_TREE_HASH_ALGORITHM
    assert current_roots == sut.DEFAULT_SOURCE_ROOTS


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
