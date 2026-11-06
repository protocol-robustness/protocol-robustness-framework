#!/usr/bin/env bash
# Explicitly prepare the immutable coprocessor source checkout in the managed
# user cache. No existing checkout is modified.
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
checkout="$cache_root/$revision"

if [[ -n "${PRF_COPROCESSOR_ROOT:-}" ]]; then
  checkout=$PRF_COPROCESSOR_ROOT
  origin=$(git -C "$checkout" remote get-url origin 2>/dev/null || true)
  case "$origin" in
    https://github.com/protocol-robustness/prf-coprocessor.git|git@github.com:protocol-robustness/prf-coprocessor.git) ;;
    *) echo "PRF_COPROCESSOR_ROOT must be the protocol-robustness/prf-coprocessor repository (found origin '$origin')" >&2; exit 5 ;;
  esac
  actual=$(git -C "$checkout" rev-parse HEAD 2>/dev/null || true)
  [[ "$actual" == "$revision" ]] || {
    echo "PRF_COPROCESSOR_ROOT must be at pinned revision $revision (found ${actual:-no Git checkout})" >&2
    exit 3
  }
  if [[ -n "$(git -C "$checkout" status --porcelain --untracked-files=all)" ]]; then
    echo "PRF_COPROCESSOR_ROOT has local changes; refusing to use or alter it" >&2
    exit 4
  fi
  echo "Using existing pinned checkout: $checkout"
  exit 0
fi

mkdir -p "$cache_root"
if ! git -C "$checkout" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  tmp="$cache_root/.clone-$revision-$$"
  trap 'rm -rf "$tmp"' EXIT
  git clone --no-checkout "$url" "$tmp"
  git -C "$tmp" checkout --detach "$revision"
  mv "$tmp" "$checkout"
  trap - EXIT
fi
actual=$(git -C "$checkout" rev-parse HEAD)
[[ "$actual" == "$revision" ]] || {
  echo "Cached checkout revision mismatch: expected $revision, found $actual at $checkout" >&2
  exit 3
}
if [[ -n "$(git -C "$checkout" status --porcelain --untracked-files=all)" ]]; then
  echo "Cached checkout has local changes at $checkout; refusing to alter it" >&2
  exit 4
fi
echo "Prepared prf-coprocessor $revision at $checkout"
