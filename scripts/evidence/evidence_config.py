"""Canonical evidence chain configuration.

Single source of truth for all evidence chain constants.
Consumers should read from this module, never hardcode paths or versions.

Usage:
    from evidence_config import EvidenceConfig

    cfg = EvidenceConfig()
    schema = cfg.schema("test-summary")
    producer = cfg.producer("summary")
    path = cfg.artifact_path("test-summary")
    artifact = cfg.artifact("test-summary")
"""

from __future__ import annotations

import json
import os
from pathlib import Path

_CONFIG_RELPATH = "config/evidence.json"


def _find_config() -> Path:
    candidates = [
        Path.cwd() / _CONFIG_RELPATH,
        Path(__file__).resolve().parent.parent / _CONFIG_RELPATH,
    ]
    for p in candidates:
        if p.exists():
            return p
    raise FileNotFoundError(
        f"evidence config not found; searched {candidates}"
    )


class EvidenceConfig:
    """Loaded evidence chain configuration."""

    def __init__(self, path: str | Path | None = None):
        p = Path(path) if path else _find_config()
        with open(p) as f:
            self._data = json.load(f)
        self._artifact_dir = Path(self.artifact_dir)
        self._artifacts_by_id = {a["id"]: a for a in self._data.get("artifacts", [])}

    # ── top-level scalars ────────────────────────────────────────────────

    @property
    def artifact_dir(self) -> str:
        return os.environ.get("PRF_ARTIFACT_DIR") or self._data["artifact_dir"]

    @property
    def contract_version(self) -> str:
        return self._data["contract_version"]

    @property
    def rounding_policy(self) -> str:
        return self._data["rounding_policy"]

    @property
    def framework(self) -> dict:
        return dict(self._data["framework"])

    @property
    def importance_map(self) -> dict:
        return dict(self._data["importance"])

    # ── resolved lookups ─────────────────────────────────────────────────

    def schema(self, key: str) -> str:
        """Resolve a schema key to its version string."""
        return self._data["schemas"][key]

    def producer(self, key: str) -> str:
        """Resolve a producer key to its ID string."""
        return self._data["producers"][key]

    def artifact(self, artifact_id: str) -> dict | None:
        """Get the full artifact definition dict by id."""
        return self._artifacts_by_id.get(artifact_id)

    def artifact_path(self, artifact_id: str) -> str:
        """Resolve an artifact id to its full path string.

        Returns absolute string path.  Raises KeyError if the artifact has no
        fixed default file (results-artifact is caller-supplied; the accepted
        registry binds the exact file via the artifact entry's ``path``).
        """
        a = self.artifact(artifact_id)
        if a is None:
            raise KeyError(f"unknown artifact id: {artifact_id}")
        f = a.get("file")
        if not f:
            raise KeyError(f"artifact {artifact_id} has no fixed file (caller-supplied)")
        return str(self._artifact_dir / f)

    def artifact_verifies_against(self, artifact_id: str) -> list[str]:
        a = self.artifact(artifact_id)
        return list(a.get("verifies_against", [])) if a else []

    def artifact_input_dependencies(self, artifact_id: str) -> list[str]:
        a = self.artifact(artifact_id)
        return list(a.get("input_dependencies", [])) if a else []

    # ── iteration ────────────────────────────────────────────────────────

    @property
    def all_artifact_ids(self) -> list[str]:
        return list(self._artifacts_by_id.keys())

    def artifacts_by_importance(self, min_importance: str = "DIAGNOSTIC") -> list[dict]:
        min_val = self.importance_map.get(min_importance, 1)
        return [
            a for a in self._data.get("artifacts", [])
            if self.importance_map.get(a["importance"], 1) <= min_val
        ]


# ── module-level singleton for convenience ────────────────────────────

_config: EvidenceConfig | None = None


def get_config(path: str | Path | None = None) -> EvidenceConfig:
    global _config
    if _config is None or path is not None:
        _config = EvidenceConfig(path)
    return _config
