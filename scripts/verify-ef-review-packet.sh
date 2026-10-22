#!/usr/bin/env bash
# Verify a generated EF technical-review packet without a source checkout.
# Usage: bash scripts/verify-ef-review-packet.sh /path/to/review-packet

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 REVIEW_PACKET_DIR" >&2
  exit 2
fi

if [ ! -d "$1" ]; then
  echo "Review packet directory does not exist: $1" >&2
  exit 2
fi
PACKET_DIR="$(cd "$1" && pwd)"
JAR="$PACKET_DIR/bin/prf-runner-sew-0.1.0-uber.jar"
CHECKSUMS="$PACKET_DIR/SHA256SUMS"
MANIFEST="$PACKET_DIR/REVIEW_PACKET_MANIFEST.json"
PROVENANCE="$PACKET_DIR/PROVENANCE.txt"
DIST_PROVENANCE="$PACKET_DIR/DISTRIBUTION_PROVENANCE.json"

for required in "$JAR" "$CHECKSUMS" "$MANIFEST" "$PROVENANCE" "$DIST_PROVENANCE"; do
  if [ ! -f "$required" ]; then
    echo "Review packet is missing required file: $required" >&2
    exit 2
  fi
done

(
  cd "$PACKET_DIR"
  java -jar "$JAR" -m resolver-sim.cli.main ref-file --check "$CHECKSUMS"
  python3 - "$PACKET_DIR" "$MANIFEST" "$PROVENANCE" "$DIST_PROVENANCE" "$CHECKSUMS" <<'PY'
import hashlib
import json
import os
import sys

root, manifest_path, provenance_path, distribution_path, checksum_path = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as f:
    manifest = json.load(f)
if manifest.get("schema_version") != "prf-ef-review-packet-manifest.v1":
    raise SystemExit("unsupported review packet manifest schema")
if manifest.get("provenance_ref") != "PROVENANCE.txt":
    raise SystemExit("manifest packet provenance path is invalid")

def path_for(relative):
    if not isinstance(relative, str) or not relative or os.path.isabs(relative):
        raise SystemExit(f"invalid packet-relative path: {relative!r}")
    path = os.path.realpath(os.path.join(root, relative))
    if os.path.commonpath([root, path]) != root or not os.path.isfile(path):
        raise SystemExit(f"missing or escaping packet path: {relative}")
    return path

def check_ref(relative, expected):
    if not isinstance(expected, str) or not expected.startswith("sha256:"):
        raise SystemExit(f"invalid sha256 reference for {relative}")
    with open(path_for(relative), "rb") as f:
        actual = "sha256:" + hashlib.sha256(f.read()).hexdigest()
    if actual != expected:
        raise SystemExit(f"manifest digest mismatch: {relative}")

jar = manifest.get("jar")
if not isinstance(jar, dict):
    raise SystemExit("manifest jar entry missing")
check_ref(jar.get("path"), jar.get("sha256"))

dist = manifest.get("distribution_provenance")
if not isinstance(dist, dict):
    raise SystemExit("manifest distribution provenance entry missing")
check_ref(dist.get("path"), dist.get("sha256"))
with open(path_for(dist["path"]), encoding="utf-8") as f:
    distribution = json.load(f)
if distribution.get("schema_version") != "prf-distribution-provenance.v1":
    raise SystemExit("unsupported distribution provenance schema")
if distribution.get("jar_sha256") != jar["sha256"]:
    raise SystemExit("distribution provenance does not identify the manifest JAR")
if distribution.get("variant") != "sew" or not distribution.get("version"):
    raise SystemExit("distribution provenance has wrong variant or missing version")
if distribution.get("jar_file") != os.path.basename(jar["path"]):
    raise SystemExit("distribution provenance names a different JAR")

with open(provenance_path, encoding="utf-8") as f:
    provenance = dict(line.rstrip("\n").split("=", 1) for line in f if "=" in line)
if provenance.get("packet_schema_version") != "prf-ef-review-packet.v1":
    raise SystemExit("unsupported packet provenance schema")
if provenance.get("repository_commit") != dist.get("source_revision"):
    raise SystemExit("packet and distribution source revisions do not match")
if provenance.get("jar_sha256") != jar["sha256"]:
    raise SystemExit("packet provenance does not identify the manifest JAR")

with open(checksum_path, encoding="utf-8") as f:
    checksum_rows = [line.rstrip("\n").split(None, 1) for line in f if line.strip()]
if any(len(row) != 2 for row in checksum_rows):
    raise SystemExit("malformed packet checksum inventory")
listed_paths = [row[1] for row in checksum_rows]
if len(listed_paths) != len(set(listed_paths)):
    raise SystemExit("duplicate path in packet checksum inventory")
actual_paths = set()
for directory, _, filenames in os.walk(root):
    for filename in filenames:
        relative = os.path.relpath(os.path.join(directory, filename), root).replace(os.sep, "/")
        if relative != "SHA256SUMS":
            actual_paths.add(relative)
if set(listed_paths) != actual_paths:
    raise SystemExit("packet checksum inventory does not cover the exact packet file set")

for section in ("additional_inputs", "evidence_profiles", "reference_vectors"):
    entries = manifest.get(section)
    if not isinstance(entries, list):
        raise SystemExit(f"manifest {section} must be an array")
    for entry in entries:
        check_ref(entry.get("path"), entry.get("sha256"))

examples = manifest.get("examples")
expected = {"scenario-rejected", "scenario-pro-rata", "scenario-semantic-failure", "benchmark-force-authorisation"}
if not isinstance(examples, list) or {e.get("id") for e in examples} != expected or len(examples) != len(expected):
    raise SystemExit("manifest examples do not match the packet's four required runs")
for example in examples:
    check_ref(example.get("input"), example.get("input_sha256"))
    bundle = example.get("bundle_root")
    bundle_path = path_for(bundle + "/completion.json")
    with open(bundle_path, encoding="utf-8") as f:
        completion = json.load(f)
    if completion.get("lifecycle_status") != "completed":
        raise SystemExit(f"incomplete evidence run: {example['id']}")
    if completion.get("semantic_status") != example.get("semantic_outcome"):
        raise SystemExit(f"manifest outcome mismatch: {example['id']}")
    verify = example.get("verify")
    expected_command = "verify-benchmark" if example["id"].startswith("benchmark-") else "verify-scenario"
    if verify != [expected_command, "--run-root", bundle]:
        raise SystemExit(f"invalid verifier command binding: {example['id']}")
    diagnostic = example.get("derived_diagnostic")
    if diagnostic:
        for key in ("mermaid", "markdown"):
            path_for(diagnostic.get(key))
    value_at_risk = example.get("value_at_risk")
    if value_at_risk:
        check_ref(value_at_risk.get("path"), value_at_risk.get("sha256"))
        path_for(value_at_risk.get("observation_path"))
print("PASS: manifest paths, references, provenance, and declared outcomes match packet contents")
PY

  java -jar "$JAR" -m resolver-sim.cli.main verify-scenario --run-root evidence/scenario-rejected
  java -jar "$JAR" -m resolver-sim.cli.main verify-scenario --run-root evidence/scenario-pro-rata
  java -jar "$JAR" -m resolver-sim.cli.main verify-scenario --run-root evidence/scenario-semantic-failure
  java -jar "$JAR" -m resolver-sim.cli.main verify-benchmark --run-root evidence/benchmark-force-authorisation
)

echo "PASS: EF review packet canonical refs, manifest, provenance, and evidence bundles verified"
