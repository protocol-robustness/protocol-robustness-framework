#!/usr/bin/env python3
"""Build a deterministic standalone source candidate for prf-canonical."""

from __future__ import annotations

import gzip
import hashlib
import io
import re
import tarfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "packages/canonical"
RELEASES = PACKAGE / "releases"
INCLUDED_ROOTS = (
    "LICENSE",
    "packages/canonical/README.md",
    "packages/canonical/RELEASE.edn",
    "packages/canonical/clj",
    "packages/canonical/rust",
    "docs/specs/evidence/CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md",
)
EXCLUDED_PARTS = {".cpcache", "releases", "target"}


def release_version() -> str:
    text = (PACKAGE / "RELEASE.edn").read_text(encoding="utf-8")
    match = re.search(r':release/version\s+"([0-9A-Za-z.+-]+)"', text)
    if not match:
        raise SystemExit("RELEASE.edn must declare :release/version")
    return match.group(1)


def included_files() -> list[Path]:
    files: set[Path] = set()
    for relative in INCLUDED_ROOTS:
        path = ROOT / relative
        if not path.exists():
            raise SystemExit(f"Missing release input: {relative}")
        if path.is_file():
            files.add(path)
            continue
        for child in path.rglob("*"):
            if child.is_file() and not any(part in EXCLUDED_PARTS for part in child.parts):
                files.add(child)
    return sorted(files, key=lambda path: path.relative_to(ROOT).as_posix())


def build() -> tuple[Path, str]:
    version = release_version()
    archive_base = f"prf-canonical-{version}"
    RELEASES.mkdir(parents=True, exist_ok=True)
    archive_path = RELEASES / f"{archive_base}.tar.gz"

    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for source in included_files():
            relative = source.relative_to(ROOT).as_posix()
            name = f"{archive_base}/{relative}"
            info = archive.gettarinfo(str(source), arcname=name)
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o755 if source.stat().st_mode & 0o111 else 0o644
            with source.open("rb") as content:
                archive.addfile(info, content)

    with archive_path.open("wb") as output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=9) as compressed:
            compressed.write(tar_buffer.getvalue())

    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (RELEASES / f"{archive_base}.tar.gz.sha256").write_text(
        f"{digest}  {archive_path.name}\n", encoding="ascii"
    )
    return archive_path, digest


if __name__ == "__main__":
    artifact, digest = build()
    print(f"Built {artifact.relative_to(ROOT)}")
    print(f"SHA-256 {digest}")
