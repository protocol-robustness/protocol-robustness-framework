# Source-bound V2 / SP1 local proving runbook

This runbook describes the **local Anvil** V2/SP1 proving path used by the
Assured Operations (AO) pro-rata conformance experiment. It does not submit a
public-chain transaction or qualify PRO-95 Stage C. The proof is an SP1 Groth16
proof over the exact V2 input supplied to the prover; it does not authenticate
external source facts or establish Base Sepolia inclusion/finality.

## Pinned components and observed run

- Coprocessor repository: `https://github.com/protocol-robustness/prf-coprocessor`
- Pinned revision from `data/prf/external-repositories.json`:
  `e2e4e9b25622c52eb028fc373a66e81b3982e933`
- Host package/binary: `allocation-sp1-script`,
  `source-bound-v2-prove`
- SP1 SDK/prover: `6.3.1`; verifier circuit and Solidity family:
  `v6.1.0`
- Installed SP1 CLI observed on this host: `cargo-prove sp1
  (8252c29 2026-06-25T11:50:01.543258355Z)`; the SP1 Rust toolchain reports
  `rustc 1.94.0-dev`
- Relevant prior attempt: source-state capture at
  `artifacts/requal-20261025-sp1-pinned-jjstore/source-state.json`, followed by
  an OOM during Groth16 proving. It captured the AO source change ID, PRF-EVM
  Git head and dirty state, source inventories, prover hash and tool versions.
  The run is explicitly incomplete; it contains no proof fixture, successful
  verifier result, AO conformance result, or final evidence manifest.

The source resolver is read-only and refuses an absent, dirty, wrong-origin or
wrong-revision coprocessor checkout. Do not update the pinned checkout while
reproducing this run. If the checkout is absent, use the repository's explicit
`scripts/setup-prf-coprocessor.sh` setup step, then rerun the resolver and pin
checks; setup may download source. The reported prior host versions were Rust/Cargo `1.98.1`
and Foundry `1.7.1`; record the actual versions for every new run.

Exact identities captured for the incomplete 2026-10-25 run:

| Item | Identity |
|---|---|
| AO working-copy change ID | `rurpymswupvlzsxlylksstrrxpvnxvos` |
| AO parent change ID | `pkuzukpzvvzutvrtwsmunloluwspzsmz` |
| PRF-EVM Git head | `fa91f060f8b286cedabaa0c758c90fbddb2fe6cd` |
| Portable AO source-inventory SHA-256 | `e0dee2ea0faa4b804ee26ada261e81173203cf6986dcd7643546350782d11969` |
| Original AO capture inventory SHA-256 | `6c96e34f4c477c5a2108ba4a07dbeb56cda54d7e882d69ed8f83d7cdc4e7ec75` |
| Portable PRF-EVM source-inventory SHA-256 | `00ef8083b85ede86b871230587b451a583061dfb880603fa8fe05fb577a8f253` |
| Original PRF-EVM capture inventory SHA-256 | `e09730343160bb70a5c35ac64e8074a132c3f28da7816afa43f4c17f197153ea` |
| Portable source-state export SHA-256 | `066fee230de63975fe40a4f24a687b2c0589b5e96cde7130c3627c9339aa3fc9` |
| Original captured source-state SHA-256 | `f8d1439d967edfa017aa397c5bd056623d463819f49c4960fedd05b390672a64` |
| Retained input SHA-256 | `c99e97a62788a6a5903431cfd710fd30a0c75025a84804325979ab4c1ccb265c` |
| Identity fixture SHA-256 | `8fc121322b2ca6c36efc00a8738f496394df4afd46d164b67f7c3e8388939bf2` |
| Prior prover binary SHA-256 | `6dea41e053796b456115cbfe96bdd1bc1147947ee6722a8e1bb838a5c0c0563d` |

The checked-in inventories are portable exports: each absolute checkout root is represented by the symbolic roots shown in `source-state.json`; per-file content hashes are unchanged. The source-state JSON is a normalized export, not the original byte-for-byte capture. Its original digest and the original inventory digests are retained above and in the export metadata. To verify the inventories, set `AO_ROOT` to the checkout under examination, `COPROCESSOR_ROOT` to the pinned coprocessor checkout, and `PRF_EVM_ROOT` to the PRF-EVM checkout. Then run from the AO repository root:

```bash
: "${AO_ROOT:=$PWD}"
: "${COPROCESSOR_ROOT:?Resolve the pinned coprocessor checkout first}"
: "${PRF_EVM_ROOT:?Set PRF_EVM_ROOT to the pinned PRF-EVM checkout}"
RUN_EVIDENCE="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/requal-20261025-sp1-pinned-jjstore"
for inventory in ao-source-inventory.sha256 prf-source-inventory.sha256; do
  sed -e "s|@AO_ROOT/|$AO_ROOT/|g" \
      -e "s|@COPROCESSOR_ROOT/|$COPROCESSOR_ROOT/|g" \
      -e "s|@PRF_EVM_ROOT/|$PRF_EVM_ROOT/|g" \
      "$RUN_EVIDENCE/$inventory" | sha256sum -c -
done
```

The PRF-EVM tree was dirty at that capture; its Git head alone is not a
complete source identity. Use the inventories and portable `source-state.json` when
comparing a reproduction.

## Prerequisites and resource check

Use a Linux host with enough free disk and memory for a locked SP1 release
build, native Groth16/gnark proving, local Foundry artifacts and the retained
run output. The exact minimum memory/disk requirement has not been measured.
One prior proving attempt was killed by the kernel OOM reaper. Do not retry on
a constrained host merely by setting an assumed parallelism variable; a
supported low-memory control has not been established for this path. Avoid a
RAM-backed `/tmp` for large build or Foundry outputs. Choose a writable
disk-backed location with ample headroom and check it with `df -h` first.

Required commands/tools:

- `bash`, `git`, `jj`, `python3`, `cargo`, `rustc`, `rustup`, `go`
- SP1 CLI/toolchain installed under `~/.sp1` (the pinned SP1 guest build uses
  the installed SP1 toolchain); add `~/.sp1/bin` to `PATH` so `cargo prove` is
  available
- `forge`, `cast`, `anvil`, `jq`, `curl`, `xxd`
- writable Cargo registry/build caches; dependencies must match the committed
  `Cargo.lock`

The full runner uses a deterministic Anvil development key when `PRIVATE_KEY`
is unset. Keep its RPC bound to loopback Anvil. Do not point
`GATE_E_RPC_URL` at a public or otherwise shared network, and do not put a real
key in `PRIVATE_KEY`.

## 1. Resolve and verify the pinned coprocessor

Run from the AO repository/workspace root:

```bash
: "${AO_ROOT:=$PWD}"
cd "$AO_ROOT"
: "${PRF_EVM_ROOT:?Set PRF_EVM_ROOT to the pinned PRF-EVM checkout}"
COPROCESSOR_ROOT="$(./scripts/resolve-prf-coprocessor.sh)"
EXPECTED_COPROCESSOR_REV=e2e4e9b25622c52eb028fc373a66e81b3982e933

test "$(git -C "$COPROCESSOR_ROOT" rev-parse HEAD)" = "$EXPECTED_COPROCESSOR_REV"
test -z "$(git -C "$COPROCESSOR_ROOT" status --porcelain --untracked-files=all)"
git -C "$PRF_EVM_ROOT" rev-parse HEAD
jj --repository "$AO_ROOT" --ignore-working-copy log -r @ --no-graph -T 'change_id'
export PATH="$HOME/.sp1/bin:$PATH"
rustc --version
cargo --version
rustup run succinct rustc --version
go version
forge --version
cargo prove --version
```

The PRF-EVM and AO source state is also captured by the full runner. For a
reproduction of the *same* attempt, compare those source inventories and
revision/dirty-state values against `source-state.json`; the prior PRF-EVM tree
was dirty and therefore is not represented by its Git head alone. A new clean
or changed source tree is a new run and must have a new run identity.

## 2. Build the prover from the lockfile

Set `CARGO_TARGET_DIR` to a writable disk-backed location with sufficient free
space. Do not reuse another source revision's compiled target as evidence.

```bash
export CARGO_TARGET_DIR=/path/on/a/disk-with-ample-space/pr95-source-bound-v2-target
export TMPDIR=/path/on/a/disk-with-ample-space/pr95-source-bound-v2-build-tmp
mkdir -p "$TMPDIR"
export PATH="$HOME/.sp1/bin:$PATH"
cargo prove --version
cargo build --locked --release \
  --manifest-path "$COPROCESSOR_ROOT/Cargo.toml" \
  -p allocation-sp1-script \
  --bin source-bound-v2-prove

PROVER="$CARGO_TARGET_DIR/release/source-bound-v2-prove"
test -x "$PROVER"
sha256sum "$PROVER" "$COPROCESSOR_ROOT/Cargo.lock"
```

Keep `--locked`; do not run `cargo update`. If the pinned dependencies are not
cached, run this in the approved build environment with access to the normal
Cargo package sources. A missing dependency or build failure is not a reason to
change the lockfile or SP1 version.

## 3. Prove an already materialized V2 input (proof-only)

The 2026-10-25 aborted local deployment left an input at:

```text
examples/assured-operations/pro-rata-conformance/artifacts/requal-20261025-sp1-pinned-jjstore/source-bound-v2-input.json
```

This input is bound to the local Anvil deployment and source state captured in
that run directory. Use a **new, empty output directory**; do not write into the
aborted run directory or overwrite any retained artifact.

```bash
: "${AO_ROOT:=$PWD}"
cd "$AO_ROOT"
export CARGO_TARGET_DIR=/path/on/a/disk-with-ample-space/pr95-source-bound-v2-target
export PATH="$HOME/.sp1/bin:$PATH"
PROVER="$CARGO_TARGET_DIR/release/source-bound-v2-prove"
INPUT="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/requal-20261025-sp1-pinned-jjstore/source-bound-v2-input.json"
RUN_TAG="$(date -u +%Y%m%dT%H%M%SZ)"
PROOF_ONLY_DIR="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/prove-only-$RUN_TAG"
FIXTURE="$PROOF_ONLY_DIR/source-bound-v2-proof-fixture.json"
export TMPDIR=/path/on/a/disk-with-ample-space/pr95-proof-only-tmp-$RUN_TAG

test -s "$INPUT"
test ! -e "$PROOF_ONLY_DIR"
mkdir -p "$PROOF_ONLY_DIR"
mkdir -p "$TMPDIR"
sha256sum "$INPUT"
SP1_PROVER=cpu "$PROVER" --input "$INPUT" --fixture "$FIXTURE"
test -s "$FIXTURE"
sha256sum "$FIXTURE"
jq -e '
  .proof_system == "groth16" and
  .program_id == "source-bound-v2-sp1-program.v2" and
  .public_values_schema == "prf/onchain-shortfall-allocation-binding-public-values.v2" and
  (.public_values | test("^0x[0-9a-fA-F]{384}$")) and
  (.proof | test("^0x[0-9a-fA-F]+$"))
' "$FIXTURE"
```

The binary derives expected public values from the input, requires exactly 192
public-value bytes, runs SP1 Groth16 proving, verifies the resulting proof
locally with the same SDK, compares the proof's public values to the derived
values, and only then writes the JSON fixture. `SP1_PROVER=cpu` explicitly
selects the local CPU prover. `--identity-only` only computes/prints the VKey
and ELF digest; it does **not** create a proof.

This proof-only path does not replay deployment, verify the Gate E manifest,
run either Foundry test suite, compare source inventories, or produce the final
AO evidence manifest. A resulting fixture alone is not a completed Gate E or
AO qualification.

## 4. Run the full fresh local qualification path

Use this for a fresh deployment, fresh input, proof, Gate E check and AO
conformance run. Set an unused local port, unique run ID and disk-backed
temporary directory. The runner refuses to overwrite artifacts, so all three
run-specific locations must be new.

```bash
: "${AO_ROOT:=$PWD}"
cd "$AO_ROOT"
: "${PRF_EVM_ROOT:?Set PRF_EVM_ROOT to the pinned PRF-EVM checkout}"
COPROCESSOR_ROOT="$(./scripts/resolve-prf-coprocessor.sh)"
export CARGO_TARGET_DIR=/path/on/a/disk-with-ample-space/pr95-source-bound-v2-target
export PATH="$HOME/.sp1/bin:$PATH"
PROVER="$CARGO_TARGET_DIR/release/source-bound-v2-prove"
RUN_TAG="$(date -u +%Y%m%dT%H%M%SZ)"
export ARTIFACT_DIR="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/requal-$RUN_TAG"
export GATE_E_EVM_ARTIFACT_DIR="$PRF_EVM_ROOT/fixtures/.ao-real-v2-requal-$RUN_TAG"
export GATE_E_PROVER="$PROVER"
export GATE_E_RPC_URL=http://127.0.0.1:18545
export SP1_PROVER=cpu
unset PRIVATE_KEY
export PRF_EVM_ROOT COPROCESSOR_ROOT
export PRF_COPROCESSOR_ROOT="$COPROCESSOR_ROOT"
export TMPDIR=/path/on/a/disk-with-ample-space/pr95-tmp-$RUN_TAG
mkdir -p "$TMPDIR"
RUN_ID="$(basename "$ARTIFACT_DIR")"
AO_TEST_ARTIFACT_DIR="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/.real-v2-test-$RUN_ID"

python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",18545)); s.close()'
test ! -e "$ARTIFACT_DIR"
test ! -e "$GATE_E_EVM_ARTIFACT_DIR"
test ! -e "$AO_TEST_ARTIFACT_DIR"
test -x "$PROVER"
bash "$AO_ROOT/examples/assured-operations/pro-rata-conformance/run-real-v2-sp1.sh"
```

Replace both `/path/on/a/disk-with-ample-space/...` values with writable,
disk-backed locations. The runner derives its AO Foundry artifact directory
internally as `artifacts/.real-v2-test-$RUN_ID`; the command computes and
checks that exact path before running. If any of the three run-specific paths
already exists, choose a different `RUN_TAG`. The EVM fixture path is
controlled by `GATE_E_EVM_ARTIFACT_DIR`.

The local port check must succeed, and no other Anvil run should start
concurrently on port `18545`. The runner starts Anvil and then uses the
configured URL; it does not independently attest which process owns that
endpoint, so keep this URL loopback-only and ensure the selected port is unused
before starting.

The script starts and stops Anvil itself. It deploys local Gate E contracts,
creates the deployment manifest and V2 witness/input, calls the prover, checks
proof identity and public-value bindings, runs the six AO proof-path cases,
runs Gate E's auxiliary consumer test, compares pre/post source inventories,
and writes `real-v2-sp1-evidence-manifest.json` last. Use only the default
local Anvil key or another dedicated Anvil-only key. This command performs no
Base Sepolia RPC activity when the RPC URL remains loopback.

## 5. Completion checks and retained outputs

A successful process exit is necessary but not sufficient. Confirm all of the
following in the unique run directory:

- `source-state.json`, pre/post source inventories and their equality;
- `source-bound-v2-input.json`, deployment manifest and proof fixture;
- proof identity, public values and manifest/route bindings pass the runner's
  checks;
- `run.log` records all six AO cases as passing and no post-run validation
  failure;
- `real-v2-sp1-evidence-manifest.json` exists and hashes the completed run
  evidence.

The runner treats Gate E's own direct-ratification/consumer test as auxiliary;
the six AO proof-path cases and post-run consistency checks gate the AO result.
The local result remains scoped to Anvil chain ID `31337`. It is not public
chain evidence, source authentication, Base finality, or proof of production
readiness. Retain failed attempts in their own directories and describe them
as failures/incomplete; never fill missing outputs or upgrade their status.

## Known resource failure

The prior exact pinned build completed, but the prover was OOM-killed during
Groth16 proving on 2026-10-25. The retained record is
`artifacts/requal-20261025-sp1-pinned-jjstore/RUN_ABORTED.md`; its input,
identity, source-state, inventories and log have individual SHA-256 values
available by running:

```bash
sha256sum examples/assured-operations/pro-rata-conformance/artifacts/requal-20261025-sp1-pinned-jjstore/*
```

The system's exact safe RAM/disk threshold is unknown. The earlier failed run
must remain incomplete unless a subsequent run independently generates and
verifies the proof and completes the local deployment/conformance checks.
