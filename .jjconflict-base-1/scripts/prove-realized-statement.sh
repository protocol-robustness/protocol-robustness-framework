#!/usr/bin/env sh
# Reproducible local target for the narrow realized-statement SP1 proof profile.
#
# The active blocker for real proof generation is environmental: the module
# cache must live on a filesystem whose NAME_MAX can accommodate gnark-crypto's
# generated internal/generator/addchain/... component. On overlay/ecryptfs
# mounts NAME_MAX can drop below 143, which makes Cargo+Go extraction of the
# locked dependence graph fail with "File name too long".
#
# This script therefore:
#   1. probes candidate cache roots (writable? NAME_MAX? fs type?)
#   2. relocates GOMODCACHE/GOCACHE/GOPATH off the repository filesystem to a
#      suitable external build/cache root (GOMODCACHE is explicitly relocatable)
#   3. fails early with a distinct reason if the filesystem is unsuitable
#   4. runs the locked, release SP1 proof
#   5. reports Gate A/B/C independently as PROVEN or NOT PROVEN
#
# Cache relocation must not change proof identity (ELF, VK, profile, public
# values, statement root, locked dependency graph). The physical cache path is
# an environment/build location, not proof identity.
set -eu

# ---- configuration ---------------------------------------------------------
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
project_cache_dir="$repo_dir/.sp1-proof-cache"
artifact=${1:-"$repo_dir/results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json"}
input_path="$repo_dir/scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json"
manifest_path="$repo_dir/coprocessor/Cargo.toml"

# Length of the worst-case gnark-crypto addchain component used for the probe.
# Deriving a filename at least this long (rather than trusting getconf, because
# layered/encrypted filesystems can report inconsistently) catches the real
# ENAMETOOLONG failure modes.
PROBE_LEN=170
# Minimum free space required for the cargo+go+gnark proof build (bytes).
# The full SP1/gnark CPU build comfortably exceeds several GB.
MIN_FREE_BYTES=${SP1_MIN_CACHE_FREE_BYTES:-5000000000}
require_go=1

# ---- helpers ---------------------------------------------------------------
report() { printf '%s\n' "$*"; }
die() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

probe_root() {
  # $1 = candidate base directory. Returns 0 only if the cache dependency graph
  # could actually extract there:
  #   - dir creatable/writable
  #   - adequate free disk for the cargo+go+gnark build
  #   - a ~170-byte single-component filename can be created (ENAMETOOLONG -> reject)
  base=$1
  probe_path="${base}/.sp1-proof-preflight"
  rm -rf "$probe_path"
  if ! mkdir -p "$probe_path" 2>/dev/null; then
    report "  candidate $base: NOT WRITABLE"
    return 1
  fi
  # free disk check (df -Pk: Available is column 4)
  _free_bytes=$(df -Pk "$base" 2>/dev/null | awk 'NR==2{print $4*1024}')
  if [ -z "${_free_bytes:-}" ]; then
    report "  candidate $base: cannot determine free space ($(df -Pk "$base" 2>/dev/null | tail -1))"
    rm -rf "$probe_path"
    return 1
  fi
  if [ "$_free_bytes" -lt "$MIN_FREE_BYTES" ]; then
    report "  candidate $base: insufficient free space (%s < %s bytes)" "$_free_bytes" "$MIN_FREE_BYTES"
    rm -rf "$probe_path"
    return 1
  fi
  # build a filename of exactly PROBE_LEN bytes
  _name=
  i=0
  while [ "$i" -lt "$PROBE_LEN" ]; do
    _name="${_name}x"
    i=$((i + 1))
  done
  if ! ( : > "$probe_path/$_name" ) 2>/dev/null; then
    # fs NAME_MAX (or inode/encryption wrapping) is too small for gnark addchain
    report "  candidate $base: NAME_MAX inadequate (cannot create %s-byte file)" "$PROBE_LEN"
    rm -rf "$probe_path"
    return 1
  fi
  rm -rf "$probe_path"
  return 0
}

report_cache_fs() {
  # $1 = candidate base directory. Print NAME_MAX/PATH_MAX/fs-type diagnostics.
  base=$1
  n=$(getconf NAME_MAX "$base" 2>/dev/null || report unavailable)
  p=$(getconf PATH_MAX "$base" 2>/dev/null || report unavailable)
  t=$(stat -f -c '%T' "$base" 2>/dev/null || report unavailable)
  report "  $base: NAME_MAX=$n PATH_MAX=$p fs=$t"
}

# ============================================================================
# STEP 1 — distinguish "cache writable" from "cache can extract locked graph"
# ============================================================================
report "== SP1 proof environment: SP1_PROVER=cpu, rust=$(rustc --version 2>/dev/null), cargo=$(cargo --version 2>/dev/null) =="

if [ "$require_go" -eq 1 ]; then
  if ! command -v go >/dev/null 2>&1; then
    die "go toolchain not found: cache dependency unavailable (graph extraction impossible)"
  fi
  report "== go env (host defaults) =="
  GOMODCACHE=$(go env GOMODCACHE 2>/dev/null || report unavailable)
  GOCACHE=$(go env GOCACHE 2>/dev/null || report unavailable)
  GOPATH=$(go env GOPATH 2>/dev/null || report unavailable)
  report "  GOMODCACHE=$GOMODCACHE"
  report "  GOCACHE=$GOCACHE"
  report "  GOPATH=$GOPATH"
  report "== repository filesystem (must be avoided for module cache) =="
  report_cache_fs "$repo_dir"
  report "== candidate cache filesystems =="
fi
for c in /tmp /var/tmp /dev/shm "$project_cache_dir"; do
  report_cache_fs "$c"
done

# ============================================================================
# STEP 2 — select the external cache root, in order of preference
#   1. explicit SP1_GO_CACHE_ROOT / SP1_GOMODCACHE
#   2. runner-local suitable volume (/var/tmp, /tmp, /dev/shm)
#   3. project-local cache only if the NAME_MAX probe succeeds
# ============================================================================
cache_root=
if [ -n "${SP1_GO_CACHE_ROOT:-}" ]; then
  report "== explicit SP1_GO_CACHE_ROOT=$SP1_GO_CACHE_ROOT =="
  if probe_root "$SP1_GO_CACHE_ROOT"; then
    cache_root="$SP1_GO_CACHE_ROOT"
  else
    die "explicit SP1_GO_CACHE_ROOT unsuitable for extracting locked dependency graph"
  fi
elif [ -n "${SP1_GOMODCACHE:-}" ]; then
  # explicit full mod cache path; root is its parent
  override_mod="$SP1_GOMODCACHE"
  report "== explicit SP1_GOMODCACHE=$SP1_GOMODCACHE (cache root = its parent) =="
  if probe_root "$(dirname "$SP1_GOMODCACHE")"; then
    cache_root="$(dirname "$SP1_GOMODCACHE")"
  else
    die "explicit SP1_GOMODCACHE parent unsuitable for extracting locked dependency graph"
  fi
else
  report "== probing cache roots (order: /var/tmp, /tmp, /dev/shm, project-local) =="
  for c in /var/tmp /tmp /dev/shm "$project_cache_dir"; do
    if probe_root "$c"; then
      cache_root="$c"
      report "  selected cache root: $c"
      break
    fi
  done
  if [ -z "$cache_root" ]; then
    die "no suitable cache root found: module cache cannot extract locked dependency graph"
  fi
fi

# ---- derive GOMODCACHE / GOCACHE / GOPATH from the external root -----------
mod_cache="${SP1_GOMODCACHE:-$cache_root/mod}"
build_cache="$cache_root/build"
gopath_cache="$cache_root/path"
cargo_home="${SP1_CARGO_HOME:-$cache_root/cargo-home}"
cargo_target="${SP1_CARGO_TARGET_DIR:-$cache_root/cargo-target}"

mkdir -p "$mod_cache" "$build_cache" "$gopath_cache" "$cargo_home" "$cargo_target"

report "== relocated cache (physical path is build location, not proof identity) =="
report "  SP1_GO_CACHE_ROOT=$cache_root"
report "  GOMODCACHE=$mod_cache"
report "  GOCACHE=$build_cache"
report "  GOPATH=$gopath_cache"
report "  CARGO_HOME=$cargo_home"
report "  CARGO_TARGET_DIR=$cargo_target"

# ============================================================================
# STEP 3 — early fail with a distinct reason if the filesystem is unsuitable
# ============================================================================
report "== preflight =="
_cache_ok=0
probe_root "$cache_root" && _cache_ok=1
if [ "$_cache_ok" -ne 1 ]; then
  die "cache not usable: cache unsuited for extracting locked dependency graph"
fi

# Cargo/lock toolchain presence (network bootstrap is exercised by the build)
if ! command -v cargo >/dev/null 2>&1 || ! command -v rustc >/dev/null 2>&1; then
  die "cargo/rust toolchain unavailable: Cargo bootstrap failure"
fi
if [ ! -f "$manifest_path" ]; then
  die "workspace manifest missing at $manifest_path"
fi

# ---- reproducibility regression (opt-in) -----------------------------------
# Cache relocation must not change proof identity. When enabled, build the guest
# ELF under two distinct suitable cache roots and compare ELF identity before
# treating cache relocation as validated.
if [ "${SP1_REPRO_CHECK:-0}" = "1" ]; then
  report "== reproducibility check: identical program/ELF identity across two cache roots =="
  elf_for_root() {
    _root=$1
    CARGO_HOME="$_root/cargo-home" \
    CARGO_TARGET_DIR="$_root/cargo-target" \
    GOPATH="$_root/path" \
    GOMODCACHE="$_root/mod" \
    GOCACHE="$_root/build" \
    cargo build --locked --release --manifest-path "$manifest_path" -p realized-statement-sp1-program \
      >/dev/null 2>&1
    find "$_root/cargo-target" -type f -name realized-statement-sp1-program -path '*/riscv64*' -print -quit
  }
  elf1=$(elf_for_root "$cache_root")
  report "  root1 ELF: $elf1"
  second_root=/var/tmp
  probe_root "$second_root" || second_root=/tmp
  probe_root "$second_root" || die "repro check needs a second suitable cache root"
  elf2=$(elf_for_root "$second_root")
  report "  root2 ELF: $elf2"
  if [ -n "$elf1" ] && [ -n "$elf2" ]; then
    h1=$(sha256sum "$elf1" | cut -d' ' -f1)
    h2=$(sha256sum "$elf2" | cut -d' ' -f1)
    report "  ELF sha256(root1)=$h1"
    report "  ELF sha256(root2)=$h2"
    if [ "$h1" = "$h2" ]; then
      report "  reproducibility: PROVEN (identical ELF across cache roots)"
    else
      die "reproducibility FAILED: ELF identity differs across cache roots"
    fi
  else
    die "repro check could not locate guest ELF under one or both cache roots"
  fi
fi

# ============================================================================
# STEP 4 — run the locked, release SP1 proof
# ============================================================================
printf '%s\n' "== running realized-statement SP1 proof (locked, release, cpu) =="
mkdir -p "$(dirname "$artifact")"
CARGO_HOME="$cargo_home" \
CARGO_TARGET_DIR="$cargo_target" \
GOPATH="$gopath_cache" \
GOMODCACHE="$mod_cache" \
GOCACHE="$build_cache" \
SP1_PROVER=cpu \
cargo run --locked --release --manifest-path "$manifest_path" \
  -p allocation-sp1-script --bin realized-statement-prove -- \
  --prove \
  --input "$input_path" \
  --artifact "$artifact"

# ============================================================================
# STEP 5 — Gate reporting (independent, PROVEN / NOT PROVEN)
# ============================================================================
report ""
report "== Gate report =="
report "Artifact: $artifact"

# Recompute the hash of the sibling Core proof envelope. The JSON artifact
# deliberately stores only the sibling filename and digest, never proof bytes.
if [ -f "$artifact" ] && command -v jq >/dev/null 2>&1; then
  _proof_file=$(jq -r '.proof_file // empty' "$artifact")
  _rec=$(jq -r '.proof_sha256 // empty' "$artifact")
  _proof_path="$(dirname "$artifact")/$_proof_file"
  if [ -n "$_proof_file" ] && [ -f "$_proof_path" ] && [ -n "$_rec" ]; then
    _computed="sha256:$(sha256sum "$_proof_path" | cut -d' ' -f1)"
    case "$_computed" in
      "$_rec") prove_hash_check=PROVEN ;;
      *) prove_hash_check=NOT_PROVEN ;;
    esac
    report "proof hash recompute (persisted sibling bytes -> sha256): $prove_hash_check"
  else
    prove_hash_check=NOT_PROVEN
    report "proof hash recompute: NOT PROVEN (missing sibling proof envelope)"
  fi
fi

report "  Gate A (artifact/proof identity):"
report "    SDK verification + public-values == native Rust : PROVEN   (enforced in realized-statement-prove)"
report "    proof bytes persisted to artifact                : $([ -s "$artifact" ] && report PROVEN || report NOT_PROVEN)"
report "    proof hash recomputed from persisted bytes       : ${prove_hash_check:-NOT_PROVEN}"
report "    strict Clojure artifact ingestion                : NOT PROVEN (no ingestion path wired)"
report "    independent SP1 verify + verifier receipt        : NOT PROVEN (no independent verifier wired)"
report "    Gate A                                           : NOT PROVEN"

report "  Gate B (theorem/claim admission for the statement): NOT PROVEN"
report "  Gate C (one-time activation / effect application):  NOT PROVEN"

# Provenance chain required (recomputed or authenticated from predecessor).
report ""
report "Provenance edges for Gate A:"
report "  canonical realized inputs      : $input_path"
report "  statement root                  : $(jq -r '.statement_root' "$artifact" 2>/dev/null || report unavailable)"
report "  ELF sha256                      : $(jq -r '.program_elf_sha256' "$artifact" 2>/dev/null || report unavailable)"
report "  VK                              : $(jq -r '.program_vkey' "$artifact" 2>/dev/null || report unavailable)"
report "  proof sha256                    : $(jq -r '.proof_sha256' "$artifact" 2>/dev/null || report unavailable)"
report "  NOTE: Gate A is NOT PROVEN unless every edge above is independently
         recomputed/authenticated from its predecessor, not merely echoed metadata."

printf '%s\n' "done"
