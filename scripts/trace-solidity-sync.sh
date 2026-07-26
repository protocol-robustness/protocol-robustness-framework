#!/usr/bin/env bash
set -euo pipefail

# trace-solidity-sync.sh
# Synchronise CDRS traces from the Clojure simulation repo into sew-protocol.
#
# Usage:
#   ./scripts/trace-solidity-sync.sh [--sew-repo <path>] [--manifest <path>]
#
# Defaults:
#   --sew-repo  ../sew-protocol  (SEW_SOLIDITY_PATH env var overrides)
#   --manifest  etc/trace-solidity-manifest.edn

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SEW_REPO="${SEW_REPO:-${SEW_SOLIDITY_PATH:-}}"
MANIFEST="${MANIFEST:-$REPO_ROOT/etc/trace-solidity-manifest.edn}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sew-repo) SEW_REPO="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

if [[ -z "$SEW_REPO" ]]; then
  echo "ERROR: --sew-repo not set and SEW_SOLIDITY_PATH/SEW_REPO not in environment"
  exit 1
fi
SEW_REPO="$(cd "$SEW_REPO" 2>/dev/null && pwd)" || {
  echo "ERROR: sew-protocol repo not found at $SEW_REPO"
  exit 1
}

if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: manifest not found at $MANIFEST"
  exit 1
fi

echo "=== trace-solidity-sync ==="
echo "  Clojure repo: $REPO_ROOT"
echo "  Sew repo:     $SEW_REPO"
echo "  Manifest:     $MANIFEST"
echo ""

# --- Parse manifest entries ---
# Simple EDN line parser for the manifest (avoids requiring a full Clojure/EDN reader)
# Extracts entries between the first {:id ...} and the final ]}
ENTRY_STARTED=0
ENTRY_TEXT=""
declare -a ENTRIES=()

while IFS= read -r line; do
  if [[ "$line" =~ ^[[:space:]]*\{\:id[[:space:]] ]]; then
    ENTRY_STARTED=1
    ENTRY_TEXT="$line"
  elif [[ "$ENTRY_STARTED" -eq 1 ]]; then
    if [[ "$line" =~ ^[[:space:]]*\} ]]; then
      ENTRY_TEXT="$ENTRY_TEXT"$'\n'"$line"
      ENTRIES+=("$ENTRY_TEXT")
      ENTRY_STARTED=0
      ENTRY_TEXT=""
    else
      ENTRY_TEXT="$ENTRY_TEXT"$'\n'"$line"
    fi
  fi
done < "$MANIFEST"

TOTAL=${#ENTRIES[@]}
COPIED=0
GENERATED=0
FAILED=0

for entry in "${ENTRIES[@]}"; do
  # Extract fields via simple pattern matching
  id=$(echo "$entry" | grep -oP '^\s*:id\s+"\K[^"]+' | head -1)
  source_path=$(echo "$entry" | grep -oP '^\s*:source\s+"\K[^"]+' | head -1)
  scenario=$(echo "$entry" | grep -oP '^\s*:scenario\s+"\K[^"]+' | head -1)
  dest_path=$(echo "$entry" | grep -oP '^\s*:destination\s+"\K[^"]+' | head -1)
  cdrs_ver=$(echo "$entry" | grep -oP '^\s*:cdrs-version\s+"\K[^"]+' | head -1)

  if [[ -z "$id" || -z "$dest_path" || -z "$cdrs_ver" ]]; then
    echo "  [SKIP] $id — missing required fields (id/destination/cdrs-version)"
    continue
  fi

  abs_source="$REPO_ROOT/$source_path"
  abs_dest="$SEW_REPO/$dest_path"
  abs_dest_dir="$(dirname "$abs_dest")"

  # Generate source trace if it doesn't exist and a scenario is specified
  if [[ ! -f "$abs_source" && -n "$scenario" ]]; then
    echo "  [GEN]  $id"
    mkdir -p "$(dirname "$abs_source")"
    abs_scenario="$REPO_ROOT/$scenario"
    if [[ ! -f "$abs_scenario" ]]; then
      echo "  [FAIL] $id — scenario file not found: $abs_scenario"
      FAILED=$((FAILED + 1))
      continue
    fi
    echo "    scenario: $scenario"
    echo "    output:   $source_path"
    if (cd "$REPO_ROOT" && clojure -M:trace-export "$abs_scenario" "$abs_source" 2>/dev/null); then
      GENERATED=$((GENERATED + 1))
    else
      echo "    [FAIL] trace-export failed for $id"
      FAILED=$((FAILED + 1))
      continue
    fi
    # Compute source SHA-256
    source_sha=$(sha256sum "$abs_source" | cut -d' ' -f1)
    echo "    sha256: $source_sha"
  elif [[ ! -f "$abs_source" ]]; then
    echo "  [FAIL] $id — source not found and no scenario specified: $source_path"
    FAILED=$((FAILED + 1))
    continue
  fi

  # Copy to destination
  echo "  [CP]   $id → $dest_path"
  mkdir -p "$abs_dest_dir"
  cp "$abs_source" "$abs_dest"

  dest_sha=$(sha256sum "$abs_dest" | cut -d' ' -f1)
  source_sha_current=$(sha256sum "$abs_source" | cut -d' ' -f1)
  echo "    source sha256:      $source_sha_current"
  echo "    destination sha256: $dest_sha"

  if [[ "$source_sha_current" != "$dest_sha" ]]; then
    echo "    [WARN] SHA-256 mismatch after copy — byte corruption?"
  fi

  COPIED=$((COPIED + 1))
done

echo ""
echo "=== Summary ==="
echo "  Total entries: $TOTAL"
echo "  Generated:     $GENERATED"
echo "  Copied:        $COPIED"
echo "  Failed:        $FAILED"
echo ""

if [[ "$FAILED" -gt 0 ]]; then
  exit 1
fi
echo "Sync complete."
