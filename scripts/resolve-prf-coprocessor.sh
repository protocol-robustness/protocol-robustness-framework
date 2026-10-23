#!/usr/bin/env bash
# Print the exact pinned coprocessor checkout. This script is read-only and
# never clones or updates a checkout; run setup-prf-coprocessor.sh explicitly.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin_file="$repo_root/data/prf/external-repositories.json"
readarray -t pin < <(python3 - "$pin_file" <<'PY'
import json, sys
entry = json.load(open(sys.argv[1]))["prf-coprocessor"]
print(entry["url"])
print(entry["revision"])
PY
)
url=${pin[0]}
revision=${pin[1]}
cache_root=${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}/prf/coprocessor
checkout=${PRF_COPROCESSOR_ROOT:-$cache_root/$revision}

if ! git -C "$checkout" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Pinned prf-coprocessor checkout not found. Run: $repo_root/scripts/setup-prf-coprocessor.sh" >&2
  exit 2
fi
origin=$(git -C "$checkout" remote get-url origin 2>/dev/null || true)
case "$origin" in
  https://github.com/protocol-robustness/prf-coprocessor.git|git@github.com:protocol-robustness/prf-coprocessor.git) ;;
  *) echo "Expected protocol-robustness/prf-coprocessor checkout, found origin '$origin'" >&2; exit 5 ;;
esac
actual=$(git -C "$checkout" rev-parse HEAD)
if [[ "$actual" != "$revision" ]]; then
  echo "prf-coprocessor revision mismatch: expected $revision, found $actual at $checkout" >&2
  exit 3
fi
if [[ -n "$(git -C "$checkout" status --porcelain --untracked-files=all)" ]]; then
  echo "prf-coprocessor checkout has local changes at $checkout; use a clean pinned checkout" >&2
  exit 4
fi
printf '%s\n' "$checkout"
