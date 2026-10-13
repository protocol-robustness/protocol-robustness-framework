"""Shared source-tree hashing for forensic scripts.

Hashes the current working tree contents under configured roots by:
1. Enumerating all regular files under the included roots
2. Sorting them by repo-relative path
3. Hashing each file's bytes with SHA-256
4. Hashing the newline-joined `path:content-sha256` manifest
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Iterable

SOURCE_TREE_HASH_ALGORITHM = "source-tree-hash.v1.path-content-sha256"
DEFAULT_SOURCE_ROOTS = ["src", "protocols_src"]


def normalize_source_roots(raw: str | Iterable[str] | None) -> list[str]:
    if raw is None:
        return list(DEFAULT_SOURCE_ROOTS)
    if isinstance(raw, str):
        roots = [part.strip() for part in raw.split(",")]
    else:
        roots = [str(part).strip() for part in raw]
    normalized = [root for root in roots if root]
    return normalized or list(DEFAULT_SOURCE_ROOTS)


def included_source_roots(repo_root: Path, roots: Iterable[str]) -> list[str]:
    return [str(root) for root in roots if (repo_root / str(root)).exists()]


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_hex(path.read_bytes())


def _iter_regular_files(repo_root: Path, roots: Iterable[str]) -> list[Path]:
    files: list[Path] = []
    for root in included_source_roots(repo_root, roots):
        root_path = repo_root / root
        if root_path.is_file():
            files.append(root_path)
            continue
        if root_path.is_dir():
            files.extend(path for path in root_path.rglob("*") if path.is_file())
    return sorted(files, key=lambda path: path.relative_to(repo_root).as_posix())


def source_tree_lines(repo_root: Path, roots: Iterable[str]) -> list[str]:
    return [
        f"{path.relative_to(repo_root).as_posix()}:{_sha256_file(path)}"
        for path in _iter_regular_files(repo_root, roots)
    ]


def compute_source_tree_hash(repo_root: Path, roots: Iterable[str]) -> tuple[str, list[str]]:
    normalized_roots = normalize_source_roots(roots)
    included_roots = included_source_roots(repo_root, normalized_roots)
    preimage = "\n".join(source_tree_lines(repo_root, included_roots)).encode("utf-8")
    return _sha256_hex(preimage), included_roots


def compute_source_byte_size(repo_root: Path, roots: Iterable[str]) -> int:
    return sum(path.stat().st_size for path in _iter_regular_files(repo_root, roots))
