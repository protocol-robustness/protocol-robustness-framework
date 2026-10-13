"""Runner identity registry per RUNNER_IDENTITY_SPEC_V1.

Supports loading identity registries, looking up runners by runner-id or
public key, and evaluating trust status.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

REGISTRY_SCHEMA_VERSION = "runner-identity-registry.v1"

VALID_STATUSES = frozenset({"trusted", "unknown", "inactive", "revoked"})

# Status → validation severity mapping
STATUS_SEVERITY = {
    "trusted": "pass",
    "unknown": "warn",
    "inactive": "fail",
    "revoked": "fail",
}


class IdentityRegistry:
    """Loaded identity registry with lookup methods."""

    def __init__(self, identities: list[dict] | None = None):
        self._by_id: dict[str, dict] = {}
        self._by_key: dict[str, dict] = {}
        if identities:
            for entry in identities:
                self._add(entry)

    def _add(self, entry: dict) -> None:
        rid = entry.get("runner/id")
        pk = entry.get("runner/public-key")
        if rid:
            self._by_id[rid] = entry
        if pk:
            self._by_key[pk] = entry

    @classmethod
    def load(cls, path: str | Path) -> "IdentityRegistry":
        """Load an identity registry from a JSON file."""
        p = Path(path).expanduser().resolve()
        data = json.loads(p.read_text())
        sv = data.get("identity-registry/schema-version")
        if sv != REGISTRY_SCHEMA_VERSION:
            raise ValueError(f"Unknown registry schema version: {sv}")
        return cls(data.get("runners", []))

    @classmethod
    def from_dicts(cls, runners: list[dict]) -> "IdentityRegistry":
        """Create a registry from a list of runner dicts."""
        return cls(runners)

    def lookup_by_id(self, runner_id: str) -> dict | None:
        """Look up a runner by runner/id. Returns the entry or None."""
        return self._by_id.get(runner_id)

    def lookup_by_key(self, public_key: str) -> dict | None:
        """Look up a runner by runner/public-key. Returns the entry or None."""
        return self._by_key.get(public_key)

    def status(self, runner_id: str | None = None,
               public_key: str | None = None) -> str:
        """Get the status of a runner.  Returns one of VALID_STATUSES."""
        entry = None
        if runner_id:
            entry = self.lookup_by_id(runner_id)
        if not entry and public_key:
            entry = self.lookup_by_key(public_key)
        if not entry:
            return "unknown"
        return entry.get("runner/status", "unknown")

    def status_severity(self, runner_id: str | None = None,
                        public_key: str | None = None) -> str:
        """Get the validation severity for a runner's status."""
        s = self.status(runner_id, public_key)
        return STATUS_SEVERITY.get(s, "warn")

    def public_key(self, runner_id: str) -> str | None:
        """Get the public key for a runner-id.  Returns None if not found."""
        entry = self.lookup_by_id(runner_id)
        if entry:
            return entry.get("runner/public-key")
        return None

    def all_runners(self) -> list[dict]:
        """Return all registered runner entries."""
        return list(self._by_id.values())

    def runner_count(self) -> int:
        return len(self._by_id)


def make_registry(runners: list[dict]) -> dict:
    """Build a registry JSON dict from a list of runner entries."""
    return {
        "identity-registry/schema-version": REGISTRY_SCHEMA_VERSION,
        "runners": runners,
    }
