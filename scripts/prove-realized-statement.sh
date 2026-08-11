#!/usr/bin/env sh
# Reproducible local target for the narrow realized-statement SP1 proof profile.
# Cache locations are intentionally project-local and excluded from Git. They
# affect build performance only; the emitted artifact records ELF/VK/profile.
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cache_dir="$repo_dir/.sp1-proof-cache"
artifact=${1:-"$repo_dir/results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json"}

mkdir -p "$cache_dir/cargo-home" "$cache_dir/cargo-target" "$cache_dir/gopath/pkg/mod"
printf '%s\n' "SP1 proof environment: SP1_PROVER=cpu, rust=$(rustc --version), cargo=$(cargo --version)"
printf '%s\n' "Artifact: $artifact"

CARGO_HOME="$cache_dir/cargo-home" \
CARGO_TARGET_DIR="$cache_dir/cargo-target" \
GOPATH="$cache_dir/gopath" \
GOMODCACHE="$cache_dir/gopath/pkg/mod" \
SP1_PROVER=cpu \
cargo run --release --manifest-path "$repo_dir/coprocessor/Cargo.toml" \
  -p allocation-sp1-script --bin realized-statement-prove -- \
  --prove \
  --input "$repo_dir/scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json" \
  --artifact "$artifact"
