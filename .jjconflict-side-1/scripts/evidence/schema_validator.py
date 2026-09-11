"""Structural validation of test-artifact registries against versioned JSON Schemas.

Loads the correct schema based on the registry's schema_version field.
Rejects unknown or unsupported schema versions.
Returns deterministic, path-aware errors distinct from semantic failures.

Usage:
    validator = SchemaValidator()
    errors = validator.validate(registry_dict)
    if errors:
        for e in errors:
            print(f"{e.path}: {e.message}")
"""

from __future__ import annotations

import json
import pathlib
import sys

import jsonschema
from jsonschema import Draft202012Validator, ValidationError

_SCHEMAS_DIR = pathlib.Path(__file__).resolve().parent.parent.parent / "schemas"

# Versions that are explicitly supported by validation.
_SUPPORTED = frozenset({"test-artifacts.v1.2"})

# The canonical schema file keyed by schema_version.
_CANONICAL_SCHEMA_FILE: dict[str, str] = {
    "test-artifacts.v1.2": "test-artifacts-v1.2.json",
}


class SchemaValidationError:
    """A single structural validation failure with a JSON path."""

    __slots__ = ("path", "message", "schema_path")

    def __init__(
        self,
        path: str,
        message: str,
        schema_path: str | None = None,
    ):
        self.path = path
        self.message = message
        self.schema_path = schema_path or ""

    def __repr__(self) -> str:
        return f"<SchemaValidationError path={self.path!r} msg={self.message!r}>"

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, SchemaValidationError):
            return NotImplemented
        return (self.path, self.message) == (other.path, other.message)

    def __hash__(self) -> int:
        return hash((self.path, self.message))


class SchemaValidator:
    """Loads and validates test-artifact registries against versioned JSON Schemas.

    Thread-safe after construction.
    """

    def __init__(self, schemas_dir: str | pathlib.Path | None = None):
        self._schemas_dir = pathlib.Path(schemas_dir) if schemas_dir else _SCHEMAS_DIR
        self._schema_cache: dict[str, dict] = {}

    # ── public API ──────────────────────────────────────────────────────────

    def supported_versions(self) -> frozenset:
        return _SUPPORTED

    def load_schema(self, version: str) -> dict:
        """Load and cache the JSON Schema for *version*.

        Raises ValueError if the version is unknown or the schema file
        cannot be read or parsed.
        """
        if version in self._schema_cache:
            return self._schema_cache[version]

        filename = _CANONICAL_SCHEMA_FILE.get(version)
        if not filename:
            raise ValueError(
                f"Unsupported schema version: {version!r}. "
                f"Supported: {sorted(_SUPPORTED)}"
            )

        schema_path = self._schemas_dir / filename
        if not schema_path.exists():
            raise ValueError(
                f"Schema file not found for version {version!r}: {schema_path}"
            )

        try:
            with schema_path.open("r", encoding="utf-8") as f:
                schema = json.load(f)
        except json.JSONDecodeError as e:
            raise ValueError(
                f"Schema file {schema_path} is not valid JSON: {e}"
            ) from e

        self._schema_cache[version] = schema
        return schema

    def resolve_schema(self, registry: dict) -> dict:
        """Determine the schema version from the registry and load it.

        Raises ValueError if the registry has no schema_version field or the
        version is unsupported.
        """
        version = registry.get("schema_version")
        if not version:
            raise ValueError(
                "Registry has no 'schema_version' field; cannot resolve schema"
            )
        if version not in _SUPPORTED:
            raise ValueError(
                f"Unsupported schema version: {version!r}. "
                f"Supported: {sorted(_SUPPORTED)}"
            )
        return self.load_schema(version)

    def validate(self, registry: dict) -> list[SchemaValidationError]:
        """Validate *registry* against its declared schema version.

        Returns a list of SchemaValidationError s. An empty list means the
        registry is structurally valid.

        Raises ValueError if the schema version is unsupported or the
        schema file cannot be loaded.
        """
        schema = self.resolve_schema(registry)
        return self._validate_against(registry, schema)

    def validate_against_version(
        self, registry: dict, version: str
    ) -> list[SchemaValidationError]:
        """Validate *registry* against a specific schema *version*.

        Useful for checking legacy registries against a known schema without
        relying on the registry's self-declared version.
        """
        schema = self.load_schema(version)
        return self._validate_against(registry, schema)

    # ── internals ──────────────────────────────────────────────────────────

    @staticmethod
    def _jsonschema_error_path(error: ValidationError) -> str:
        """Convert a jsonschema error path to a JSON path string.

        Example: ('artifacts', 0, 'sha256') → "$.artifacts[0].sha256"
        """
        parts: list[str] = []
        for elem in error.absolute_path:
            if isinstance(elem, int):
                parts.append(f"[{elem}]")
            else:
                if parts and not parts[-1].startswith("["):
                    parts.append(".")
                parts.append(str(elem))
        return "$" + "".join(parts) if parts else "$"

    @staticmethod
    def _schema_path(error: ValidationError) -> str:
        parts: list[str] = []
        for elem in error.absolute_schema_path:
            if isinstance(elem, int):
                parts.append(f"[{elem}]")
            else:
                if parts and not parts[-1].startswith("["):
                    parts.append(".")
                parts.append(str(elem))
        return "#" + "".join(parts) if parts else "#"

    def _validate_against(
        self, registry: dict, schema: dict
    ) -> list[SchemaValidationError]:
        validator = Draft202012Validator(schema)
        errors: list[SchemaValidationError] = []
        for error in validator.iter_errors(registry):
            errors.append(
                SchemaValidationError(
                    path=self._jsonschema_error_path(error),
                    message=error.message,
                    schema_path=self._schema_path(error),
                )
            )
        # Sort by path for deterministic output.
        errors.sort(key=lambda e: e.path)
        return errors


# ── convenience ──────────────────────────────────────────────────────────────

def validate_registry(
    registry: dict,
    schemas_dir: str | pathlib.Path | None = None,
) -> list[SchemaValidationError]:
    """One-shot convenience: create a validator and validate *registry*."""
    return SchemaValidator(schemas_dir).validate(registry)


def validate_registry_file(
    path: str | pathlib.Path,
    schemas_dir: str | pathlib.Path | None = None,
) -> list[SchemaValidationError]:
    """Read a registry JSON file and validate it."""
    p = pathlib.Path(path)
    with p.open("r", encoding="utf-8") as f:
        registry = json.load(f)
    return validate_registry(registry, schemas_dir)


# ── CLI ──────────────────────────────────────────────────────────────────────

def _main() -> int:
    import argparse

    ap = argparse.ArgumentParser(
        description="Validate a test-artifact registry JSON file against its schema."
    )
    ap.add_argument("registry_file", type=str, help="Path to test-artifacts.json")
    ap.add_argument(
        "--schemas-dir",
        type=str,
        default=None,
        help="Override schemas directory (default: ../schemas relative to this script)",
    )
    ap.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Print detailed error information",
    )
    args = ap.parse_args()

    try:
        errors = validate_registry_file(args.registry_file, args.schemas_dir)
    except ValueError as e:
        print(f"[schema-validator] ERROR: {e}", file=sys.stderr)
        return 1
    except FileNotFoundError as e:
        print(f"[schema-validator] ERROR: {e}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"[schema-validator] ERROR: {e}", file=sys.stderr)
        return 1

    if not errors:
        print("[schema-validator] PASS: registry is structurally valid")
        return 0

    print(f"[schema-validator] FAIL: {len(errors)} structural error(s)")
    for e in errors:
        line = f"  {e.path}: {e.message}"
        if args.verbose and e.schema_path:
            line += f"  (schema: {e.schema_path})"
        print(line)
    return 1


if __name__ == "__main__":
    sys.exit(_main())
