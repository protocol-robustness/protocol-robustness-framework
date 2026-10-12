#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(realpath -m "${1:?provide a new output directory}")
EVM=${PRF_EVM_ROOT:-"$(dirname "$ROOT")/prf-evm"}
if [[ ! -d "$EVM" && -d "$(dirname "$(dirname "$ROOT")")/prf-evm" ]]; then EVM="$(dirname "$(dirname "$ROOT")")/prf-evm"; fi
[[ -d "$EVM" ]] || { echo 'Set PRF_EVM_ROOT to the owning EVM checkout' >&2; exit 1; }
[[ ! -e "$OUT" ]] || { echo 'Output directory already exists' >&2; exit 1; }
mkdir -p "$OUT"
cd "$ROOT"
export RAYON_NUM_THREADS=${RAYON_NUM_THREADS:-8}
export GOMAXPROCS=${GOMAXPROCS:-8}
export GOMEMLIMIT=${GOMEMLIMIT:-20GiB}
export SP1_WORKER_NUM_CORE_WORKERS=${SP1_WORKER_NUM_CORE_WORKERS:-1}
export SP1_WORKER_CORE_BUFFER_SIZE=${SP1_WORKER_CORE_BUFFER_SIZE:-1}
export SP1_WORKER_NUM_RECURSION_PROVER_WORKERS=${SP1_WORKER_NUM_RECURSION_PROVER_WORKERS:-1}
export SP1_WORKER_RECURSION_PROVER_BUFFER_SIZE=${SP1_WORKER_RECURSION_PROVER_BUFFER_SIZE:-1}
export CLJ_CONFIG=${CLJ_CONFIG:-/tmp/partial-fill-clj-config}
cargo build --offline --manifest-path coprocessor/Cargo.toml -p allocation-kernel --bin controlled-entitlement-source-replay > "$OUT/native-build.log" 2>&1
cargo build --offline --release --manifest-path coprocessor/Cargo.toml -p allocation-sp1-script --bin controlled-entitlement-source-prove --bin controlled-entitlement-source-wrap --bin controlled-entitlement-source-resume > "$OUT/guest-build.log" 2>&1
TARGET=${CARGO_TARGET_DIR:-"$ROOT/coprocessor/target"}
REPLAY="$TARGET/debug/controlled-entitlement-source-replay"
PROVER="$TARGET/release/controlled-entitlement-source-prove"
WRAPPER="$TARGET/release/controlled-entitlement-source-wrap"
RESUME="$TARGET/release/controlled-entitlement-source-resume"
ARTIFACTS=${SP1_GROTH16_ARTIFACTS:?set SP1_GROTH16_ARTIFACTS to the installed SDK circuit directory}
clojure -M examples/partial-fill/materialize.clj init "$OUT"
"$PROVER" --input "$OUT/identity-input.json" --execute > "$OUT/guest-execute.log" 2>&1
SP1_PROVER=cpu "$PROVER" --input "$OUT/identity-input.json" --identity-only > "$OUT/identity.log" 2>&1
python3 - "$OUT" <<'PY'
import sys,re,json,pathlib
p=pathlib.Path(sys.argv[1]); text=(p/'identity.log').read_text()
keys=['program_id','vkey','elf_sha256']; identity={k:re.search(r'^'+k+r': (\S+)$',text,re.M)[1] for k in keys}
(p/'identity.json').write_text(json.dumps(identity,indent=2)+'\n')
PY
cp "$TARGET/elf-compilation/riscv64im-succinct-zkvm-elf/release/controlled-entitlement-source-sp1-program" "$OUT/controlled-program.elf"
mkdir -p "$EVM/test/fixtures/partial-fill"
cp "$OUT/identity.json" "$EVM/test/fixtures/partial-fill/"
export PARTIAL_FILL_STAGE=0
export_context() {
  local name=$1 file=$2
  (cd "$EVM"; forge test --match-path test/PartialFillControlled.t.sol --match-test "$name" --offline -vv) > "$OUT/$file.log" 2>&1
  python3 - "$OUT/$file.log" "$OUT/$file.json" <<'PY'
import sys,json,pathlib
line=next(x for x in pathlib.Path(sys.argv[1]).read_text().splitlines() if 'PARTIAL_FILL_JSON {' in x)
pathlib.Path(sys.argv[2]).write_text(json.dumps(json.loads(line.split('PARTIAL_FILL_JSON ',1)[1]),indent=2)+'\n')
PY
}
export_context test_ExportBootstrap bootstrap
clojure -M examples/partial-fill/materialize.clj configuration "$OUT" "$OUT/bootstrap.json"
cp "$OUT/configuration.json" "$EVM/test/fixtures/partial-fill/"
export PARTIAL_FILL_STAGE=1
export_context test_ExportGenesis genesis-context
clojure -M examples/partial-fill/materialize.clj genesis "$OUT" "$OUT/genesis-context.json"
cp "$OUT/genesis.json" "$EVM/test/fixtures/partial-fill/"
export PARTIAL_FILL_STAGE=2
export_context test_ExportWitness context
export_context test_ExportRecoveryWitness recovery-context
for prefix in '' recovery-; do
  clojure -M examples/partial-fill/materialize.clj witness "$OUT" "$OUT/${prefix}context.json" "$prefix"
  "$REPLAY" < "$OUT/${prefix}witness.json" > "$OUT/${prefix}replay.json"
  python3 - "$OUT/$prefix" <<'PY'
import sys,json
p=sys.argv[1]; assert json.load(open(p+'replay.json'))==json.load(open(p+'clojure-replay.json')), 'Independent replay mismatch'
PY
  RUST_LOG=info "$WRAPPER" --input "$OUT/${prefix}witness.json" --wrapper-witness "$OUT/${prefix}wrapper-witness.json" --compressed-proof "$OUT/${prefix}compressed.bin" > "$OUT/${prefix}wrap.log" 2>&1
  RUST_LOG=info "$RESUME" --input "$OUT/${prefix}witness.json" --wrapper-witness "$OUT/${prefix}wrapper-witness.json" --groth16-artifacts "$ARTIFACTS" --fixture "$OUT/${prefix}proof.json" > "$OUT/${prefix}prove.log" 2>&1
  cp "$OUT/${prefix}proof.json" "$EVM/test/fixtures/partial-fill/"
done
cp "$OUT/replay.json" "$EVM/test/fixtures/partial-fill/"
python3 examples/partial-fill/qualify-source.py --replay "$REPLAY" --witness "$OUT/witness.json" --output "$OUT/negative-source"
(cd "$EVM"; forge test --match-path test/PartialFillControlled.t.sol --match-test 'test_(Qualification|Reject)' --offline --json -vv) > "$OUT/evm-tests.json"
(cd "$EVM"; forge test --match-path 'test/{SourceBoundProRataEntitlementAssuranceV1,SourceBoundProRataEntitlementRatificationControllerV1,OnchainShortfallReserveV2,ProductionProRataAllocatorV2,ProductionProRataAllocatorV2Withdrawal,PublicPackedEntitlementRepresentationV1,PrfSourceBoundEntitlementVerifierAdapterV1,SourceBoundEntitlementV1Commitments}.t.sol' --offline --json) > "$OUT/regression-tests.json"
python3 examples/partial-fill/report.py "$OUT"
python3 examples/partial-fill/package-evidence.py "$OUT" "$EVM"
