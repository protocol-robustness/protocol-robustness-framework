#!/usr/bin/env bash
set -Eeuo pipefail

# Fresh Gate E deployment/proof, followed by the AO dispatcher conformance test.
# Gate E's own direct-ratification test runs afterward as an auxiliary check.

AO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PRF_EVM_ROOT="${PRF_EVM_ROOT:-$(cd "$AO_ROOT/../../prf-evm" && pwd)}"
ARTIFACT_DIR="$AO_ROOT/examples/assured-operations/pro-rata-conformance/artifacts/real-v2-sp1"
IDENTITY="$PRF_EVM_ROOT/fixtures/source-bound-v2-identity.json"
PROVER="$AO_ROOT/coprocessor/target/release/source-bound-v2-prove"

mkdir -p "$ARTIFACT_DIR"
export GATE_E_RPC_URL="${GATE_E_RPC_URL:-http://127.0.0.1:8545}"
export GATE_E_IDENTITY="$IDENTITY"
export GATE_E_MANIFEST="$ARTIFACT_DIR/gate-e-deployment-manifest.json"
export GATE_E_FIXTURE="$ARTIFACT_DIR/source-bound-v2-proof-fixture.json"
export GATE_E_INPUT="$ARTIFACT_DIR/source-bound-v2-input.json"
export GATE_E_AO_ROUTE_MANIFEST="$ARTIFACT_DIR/ao-route-manifest.json"
SOURCE_STATE="$ARTIFACT_DIR/source-state.json"
RUN_LOG="$ARTIFACT_DIR/run.log"
AO_INVENTORY="$ARTIFACT_DIR/ao-source-inventory.sha256"
PRF_INVENTORY="$ARTIFACT_DIR/prf-source-inventory.sha256"
AO_POST_INVENTORY="$ARTIFACT_DIR/ao-source-inventory-post.sha256"
PRF_POST_INVENTORY="$ARTIFACT_DIR/prf-source-inventory-post.sha256"
EVIDENCE_MANIFEST="$ARTIFACT_DIR/real-v2-sp1-evidence-manifest.json"

: "${PRIVATE_KEY:=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80}"
export PRIVATE_KEY

# Local Anvil-only semantic roots and operation inputs. The V2 prover input is
# materialized from live deployed addresses/state by Gate E before proving.
export GATE_E_PROTOCOL_GENESIS_ROOT=0x1111111111111111111111111111111111111111111111111111111111111111
export GATE_E_AUTHORITY_STATE_ROOT=0x2222222222222222222222222222222222222222222222222222222222222222
export GATE_E_CHAIN_INSTANCE_GENESIS_ROOT=0x5555555555555555555555555555555555555555555555555555555555555555
export GATE_E_CONFIGURATION_ROOT_0=0x3333333333333333333333333333333333333333333333333333333333333333
export GATE_E_SOURCE_PROGRAM_VKEY="$(jq -r '.vkey' "$IDENTITY")"
export GATE_E_SOURCE_PROGRAM_ELF_SHA256="$(jq -r '.elf_sha256' "$IDENTITY")"
export GATE_E_OPERATION_ID=0x0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a
export GATE_E_POLICY_ROOT=0x0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f
export GATE_E_ALLOCATION_BINDING_ROOT=0x1010101010101010101010101010101010101010101010101010101010101010
export GATE_E_REALIZED_STATEMENT_ROOT=0x1111111111111111111111111111111111111111111111111111111111111111
export GATE_E_RESIDUAL_RECIPIENT=0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266
export GATE_E_SOURCE_VERIFIER_ID="0x$(printf '0b%.0s' {1..32})"
export GATE_E_EXECUTION_VERIFIER_ID=0x0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c
export GATE_E_SOURCE_STATEMENT_SCHEMA="$(cast keccak "$(jq -r '.statement_schema' "$IDENTITY")")"
export GATE_E_EXECUTION_STATEMENT_SCHEMA="0x$(printf '0d%.0s' {1..32})"
export GATE_E_EXECUTION_PUBLIC_VALUES_SCHEMA="0x$(printf '0e%.0s' {1..32})"

test -s "$IDENTITY" || { echo "Missing pinned V2 identity: $IDENTITY" >&2; exit 2; }
command -v xxd >/dev/null || { echo "xxd is required for retained-proof consistency checks" >&2; exit 2; }
test -x "$PROVER" || {
    echo "Missing prover binary. Build it with:" >&2
    echo "cargo build --release --manifest-path '$AO_ROOT/coprocessor/Cargo.toml' -p allocation-sp1-script --bin source-bound-v2-prove" >&2
    exit 2
}
for artifact in "$GATE_E_MANIFEST" "$GATE_E_FIXTURE" "$GATE_E_INPUT" "$GATE_E_AO_ROUTE_MANIFEST" "$SOURCE_STATE" "$RUN_LOG" "$AO_INVENTORY" "$PRF_INVENTORY" "$AO_POST_INVENTORY" "$PRF_POST_INVENTORY" "$EVIDENCE_MANIFEST"; do
    test ! -e "$artifact" || { echo "Refusing to overwrite retained artifact: $artifact" >&2; exit 2; }
done

# Preserve source identity even when this JJ workspace cannot snapshot because
# its shared Git object store is read-only. Revision IDs are recorded where
# available; the deterministic file inventories also cover dirty/untracked
# source files that a revision ID alone would miss.
AO_CHANGE_ID="$(jj log --ignore-working-copy -r @ --no-graph -T 'change_id')"
AO_PARENT_CHANGE_ID="$(jj log --ignore-working-copy -r @- --no-graph -T 'change_id')"
PRF_GIT_HEAD="$(git -C "$PRF_EVM_ROOT" rev-parse HEAD)"
PRF_GIT_STATUS="$(git -C "$PRF_EVM_ROOT" status --porcelain=v1 --untracked-files=all)"
PRF_DIFF_SHA256="$(git -C "$PRF_EVM_ROOT" diff --binary HEAD | sha256sum | awk '{print $1}')"
PRF_SUBMODULE_STATUS="$(git -C "$PRF_EVM_ROOT" submodule status --recursive)"
AO_FILES="$AO_INVENTORY"
PRF_FILES="$PRF_INVENTORY"
find "$AO_ROOT/examples/assured-operations/src" \
     "$AO_ROOT/examples/assured-operations/pro-rata-conformance" \
     -type f ! -path '*/out/*' ! -path '*/cache/*' ! -path '*/artifacts/*' -print0 \
  | sort -z | xargs -0 sha256sum > "$AO_FILES"
find "$PRF_EVM_ROOT/src" "$PRF_EVM_ROOT/script" "$PRF_EVM_ROOT/scripts" \
     "$PRF_EVM_ROOT/lib/openzeppelin-contracts/contracts" \
     "$PRF_EVM_ROOT/lib/sp1-contracts/contracts/src" \
     "$PRF_EVM_ROOT/lib/forge-std/src" \
     "$PRF_EVM_ROOT/test/GateEDeploymentDerivedRealProof.t.sol" \
     "$PRF_EVM_ROOT/fixtures/source-bound-v2-identity.json" \
     "$PRF_EVM_ROOT/foundry.toml" "$PRF_EVM_ROOT/foundry.lock" \
     -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum > "$PRF_FILES"
find "$AO_ROOT/coprocessor/src" -type f -print0 | sort -z | xargs -0 sha256sum >> "$AO_FILES"
sha256sum "$AO_ROOT/coprocessor/Cargo.toml" "$AO_ROOT/coprocessor/Cargo.lock" >> "$AO_FILES"
AO_SOURCE_SHA256="$(sha256sum "$AO_FILES" | awk '{print $1}')"
PRF_SOURCE_SHA256="$(sha256sum "$PRF_FILES" | awk '{print $1}')"
jq -n \
  --arg capturedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg aoWorkingCopyChangeId "$AO_CHANGE_ID" \
  --arg aoParentChangeId "$AO_PARENT_CHANGE_ID" \
  --arg aoRelevantSourceInventorySha256 "$AO_SOURCE_SHA256" \
  --arg chainInstanceGenesisRoot "$GATE_E_CHAIN_INSTANCE_GENESIS_ROOT" \
  --arg prfGitHead "$PRF_GIT_HEAD" \
  --arg prfGitStatus "$PRF_GIT_STATUS" \
  --arg prfSubmoduleStatus "$PRF_SUBMODULE_STATUS" \
  --arg prfTrackedDiffSha256 "$PRF_DIFF_SHA256" \
  --arg prfRelevantSourceInventorySha256 "$PRF_SOURCE_SHA256" \
  --arg proverSha256 "$(sha256sum "$PROVER" | awk '{print $1}')" \
  --arg rustcVersion "$(rustc --version)" \
  --arg cargoVersion "$(cargo --version)" \
  --arg forgeVersion "$(forge --version | sed -n '1p')" \
  '{schema:"ao-real-v2-source-state.v1",capturedAt:$capturedAt,
    assuredOperations:{jjWorkingCopyChangeId:$aoWorkingCopyChangeId,
      jjParentChangeId:$aoParentChangeId,relevantSourceInventorySha256:$aoRelevantSourceInventorySha256,
      proverSha256:$proverSha256,rustcVersion:$rustcVersion,cargoVersion:$cargoVersion},
    runnerConfiguration:{chainInstanceGenesisRoot:$chainInstanceGenesisRoot},
    prfEvm:{gitHead:$prfGitHead,gitStatusPorcelain:$prfGitStatus,
      gitSubmoduleStatus:$prfSubmoduleStatus,
      trackedDiffSha256:$prfTrackedDiffSha256,relevantSourceInventorySha256:$prfRelevantSourceInventorySha256,
      forgeVersion:$forgeVersion}}' \
  > "$SOURCE_STATE"

TMP_BUILD="${TMPDIR:-/tmp}/assured-operations-real-v2-sp1"
mkdir -p "$TMP_BUILD"
export FOUNDRY_OUT="$TMP_BUILD/prf-evm-out"
export FOUNDRY_CACHE_PATH="$TMP_BUILD/prf-evm-cache"
export FOUNDRY_BROADCAST="$TMP_BUILD/prf-evm-broadcast"

export GATE_E_DEPLOY_CMD="forge script '$PRF_EVM_ROOT/script/DeployGateEOptionA.s.sol:DeployGateEOptionA' --root '$PRF_EVM_ROOT' --rpc-url \"\$GATE_E_RPC_URL\" --private-key \"\$PRIVATE_KEY\" --broadcast --out \"\$FOUNDRY_OUT\" --cache-path \"\$FOUNDRY_CACHE_PATH\""
export GATE_E_PROVER_CMD="'$PROVER' --input \"\$GATE_E_INPUT\" --fixture \"\$GATE_E_FIXTURE\" && forge test --root '$AO_ROOT/examples/assured-operations/pro-rata-conformance' --fork-url \"\$GATE_E_RPC_URL\" --match-contract ProRataRealProofConformanceTest --out '$TMP_BUILD/ao-out' --cache-path '$TMP_BUILD/ao-cache' -vv"

cd "$PRF_EVM_ROOT"
RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SECONDS=0
printf 'AO_REAL_V2_RUN_STARTED_UTC=%s\n' "$RUN_STARTED_AT" | tee "$RUN_LOG"
set +e
./scripts/gate-e-e2e.sh 2>&1 | tee -a "$RUN_LOG"
GATE_E_RUNNER_EXIT_CODE="${PIPESTATUS[0]}"
set -e
printf 'AO_REAL_V2_RUN_COMPLETED_UTC=%s\nAO_REAL_V2_RUN_DURATION_SECONDS=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SECONDS" | tee -a "$RUN_LOG"
fail_post() {
    printf 'POST_RUN_VALIDATION_FAILURE=%s\n' "$1" | tee -a "$RUN_LOG" >&2
    exit 5
}

# Link all retained run materials from the successful AO route record. This is
# intentionally produced only after both AO and Gate E verification complete.
test -s "$GATE_E_AO_ROUTE_MANIFEST" || fail_post "AO route manifest was not produced"
AO_TESTS=(
    testRealProofAndExactOperationExecuteThroughFrozenAOPath
    testWrongProofRejectsWithoutNonceOrCapitalTransition
    testProofCannotBeReboundToAnotherPreparedOperation
    testProofCannotBeSubstitutedForAnotherPreparedDeploymentRoot
    testConfigurationMismatchRejectsAndConsumesNothing
    testStaleProjectionRejectsWithoutV2OrAOConsumption
)
for test_name in "${AO_TESTS[@]}"; do
    grep -Eq "\\[PASS\\].*${test_name}" "$RUN_LOG" || fail_post "AO real-proof case did not pass: $test_name"
done
if [[ "$GATE_E_RUNNER_EXIT_CODE" -eq 0 ]]; then
    GATE_E_AUXILIARY_STATUS="passed"
else
    GATE_E_AUXILIARY_STATUS="failed-or-post-AO-check-failed"
fi
printf 'AO_REAL_V2_CASES=6/6-passed\nGATE_E_AUXILIARY_EXIT_CODE=%s\nGATE_E_AUXILIARY_STATUS=%s\n' \
  "$GATE_E_RUNNER_EXIT_CODE" "$GATE_E_AUXILIARY_STATUS" | tee -a "$RUN_LOG"

# Verify source files were stable across deployment, proving, and both test paths.
find "$AO_ROOT/examples/assured-operations/src" \
     "$AO_ROOT/examples/assured-operations/pro-rata-conformance" \
     -type f ! -path '*/out/*' ! -path '*/cache/*' ! -path '*/artifacts/*' -print0 \
  | sort -z | xargs -0 sha256sum > "$AO_POST_INVENTORY"
find "$PRF_EVM_ROOT/src" "$PRF_EVM_ROOT/script" "$PRF_EVM_ROOT/scripts" \
     "$PRF_EVM_ROOT/lib/openzeppelin-contracts/contracts" \
     "$PRF_EVM_ROOT/lib/sp1-contracts/contracts/src" \
     "$PRF_EVM_ROOT/lib/forge-std/src" \
     "$PRF_EVM_ROOT/test/GateEDeploymentDerivedRealProof.t.sol" \
     "$PRF_EVM_ROOT/fixtures/source-bound-v2-identity.json" \
     "$PRF_EVM_ROOT/foundry.toml" "$PRF_EVM_ROOT/foundry.lock" \
     -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum > "$PRF_POST_INVENTORY"
find "$AO_ROOT/coprocessor/src" -type f -print0 | sort -z | xargs -0 sha256sum >> "$AO_POST_INVENTORY"
sha256sum "$AO_ROOT/coprocessor/Cargo.toml" "$AO_ROOT/coprocessor/Cargo.lock" >> "$AO_POST_INVENTORY"
if ! cmp -s "$AO_INVENTORY" "$AO_POST_INVENTORY" || ! cmp -s "$PRF_INVENTORY" "$PRF_POST_INVENTORY"; then
    fail_post "Relevant source files changed during the run; pre/post inventories retained for diagnosis"
fi

# Cross-check the public values actually consumed by AO against both the
# deployment manifest and the AO route record before producing the final index.
PUBLIC_VALUES="$(jq -r '.public_values | ascii_downcase' "$GATE_E_FIXTURE")"
test "${#PUBLIC_VALUES}" -eq 386 || fail_post "Unexpected public-values byte length"
public_word() { local n="$1"; local offset=$((2 + n * 64)); printf '0x%s' "${PUBLIC_VALUES:offset:64}"; }
for pair in \
    "1:preparedOperationRoot" \
    "2:reserveStateCommitment" \
    "3:allocationBindingRoot" \
    "4:realizedStatementRoot" \
    "5:operationId"; do
    index="${pair%%:*}"
    field="${pair#*:}"
    expected="$(jq -r ".$field | ascii_downcase" "$GATE_E_MANIFEST")"
    [[ "$(public_word "$index")" == "$expected" ]] || fail_post "Public-values $field differs from deployment manifest"
    route_value="$(jq -r ".$field | ascii_downcase" "$GATE_E_AO_ROUTE_MANIFEST")"
    [[ "$(public_word "$index")" == "$route_value" ]] || fail_post "Public-values $field differs from AO-dispatched route"
done

proof_sha="$(printf '%s' "$(jq -r '.proof | ltrimstr("0x")' "$GATE_E_FIXTURE")" | xxd -r -p | sha256sum | awk '{print $1}')"
public_values_sha="$(printf '%s' "${PUBLIC_VALUES#0x}" | xxd -r -p | sha256sum | awk '{print $1}')"
[[ "0x$proof_sha" == "$(jq -r '.proofSha256 | ascii_downcase' "$GATE_E_AO_ROUTE_MANIFEST")" ]] || fail_post "AO route proof digest differs from retained proof"
[[ "0x$public_values_sha" == "$(jq -r '.publicValuesSha256 | ascii_downcase' "$GATE_E_AO_ROUTE_MANIFEST")" ]] || fail_post "AO route public-values digest differs from retained fixture"
CALLDATA="$(jq -r '.operationCalldata | ascii_downcase' "$GATE_E_AO_ROUTE_MANIFEST")"
test "${#CALLDATA}" -eq 330 || fail_post "Unexpected ratifyAndFund calldata length"
calldata_sha="$(printf '%s' "${CALLDATA#0x}" | xxd -r -p | sha256sum | awk '{print $1}')"
[[ "0x$calldata_sha" == "$(jq -r '.operationCalldataHash | ascii_downcase' "$GATE_E_AO_ROUTE_MANIFEST")" ]] || fail_post "Route calldata does not match its recorded hash"
[[ "0x${CALLDATA:2:8}" == "$(jq -r '.operationSelector | ascii_downcase' "$GATE_E_AO_ROUTE_MANIFEST")" ]] || fail_post "Route calldata selector differs from recorded selector"
jq -e --slurpfile deployment "$GATE_E_MANIFEST" --slurpfile route "$GATE_E_AO_ROUTE_MANIFEST" '
  ($route[0].gateEManifestDigest == $deployment[0].manifestDigest) and
  ($route[0].chainId == $deployment[0].chainId) and
  (($route[0].configurationRoot | ascii_downcase) == ($deployment[0].configurationRoot | ascii_downcase)) and
  ($route[0].configurationEpoch == $deployment[0].configurationEpoch) and
  (($route[0].programVKey | ascii_downcase) == ($deployment[0].provenance["canonical-external"].vkey | ascii_downcase)) and
  (($route[0].programElfSha256 | ascii_downcase) == ($deployment[0].provenance["canonical-external"].elfSha256 | ascii_downcase)) and
  (($route[0].ratificationController | ascii_downcase) == ($deployment[0].contracts.ratificationController | ascii_downcase)) and
  (($route[0].permanentlyBoundExecutionCaller | ascii_downcase) == ($route[0].dispatchTarget | ascii_downcase)) and
  (($route[0].operationTarget | ascii_downcase) == ($route[0].dispatchTarget | ascii_downcase)) and
  (($route[0].operationPrincipal | ascii_downcase) == ($deployment[0].deployer | ascii_downcase)) and
  ($route[0].consumedRegistrationRoot == $route[0].registrationRoot) and
  ($route[0].consumedRegistrationRevision == $route[0].registrationRevision)
' "$GATE_E_AO_ROUTE_MANIFEST" >/dev/null || fail_post "AO route record does not match deployment manifest"
jq -e --slurpfile route "$GATE_E_AO_ROUTE_MANIFEST" --slurpfile source "$SOURCE_STATE" '
  (($route[0].chainInstanceGenesisRoot | ascii_downcase) == ($source[0].runnerConfiguration.chainInstanceGenesisRoot | ascii_downcase))
' "$GATE_E_AO_ROUTE_MANIFEST" >/dev/null || fail_post "AO route chain-instance root differs from recorded runner configuration"

# This is the terminal evidence index: the route record is produced by the AO
# test, the log is finalized, and only then are their hashes written here.
jq -n --slurpfile route "$GATE_E_AO_ROUTE_MANIFEST" \
  --arg schema "ao-real-v2-sp1-evidence-manifest.v1" \
  --arg deploymentManifestSha256 "$(sha256sum "$GATE_E_MANIFEST" | awk '{print $1}')" \
  --arg proverInputSha256 "$(sha256sum "$GATE_E_INPUT" | awk '{print $1}')" \
  --arg proofFixtureSha256 "$(sha256sum "$GATE_E_FIXTURE" | awk '{print $1}')" \
  --arg aoRouteManifestSha256 "$(sha256sum "$GATE_E_AO_ROUTE_MANIFEST" | awk '{print $1}')" \
  --arg sourceStateSha256 "$(sha256sum "$SOURCE_STATE" | awk '{print $1}')" \
  --arg aoPreSourceInventorySha256 "$(sha256sum "$AO_INVENTORY" | awk '{print $1}')" \
  --arg aoPostSourceInventorySha256 "$(sha256sum "$AO_POST_INVENTORY" | awk '{print $1}')" \
  --arg prfPreSourceInventorySha256 "$(sha256sum "$PRF_INVENTORY" | awk '{print $1}')" \
  --arg prfPostSourceInventorySha256 "$(sha256sum "$PRF_POST_INVENTORY" | awk '{print $1}')" \
  --arg runLogSha256 "$(sha256sum "$RUN_LOG" | awk '{print $1}')" \
  --arg runStartedAt "$RUN_STARTED_AT" \
  --arg runDurationSeconds "$SECONDS" \
  --arg aoConformanceStatus "passed" \
  --arg gateEAuxiliaryStatus "$GATE_E_AUXILIARY_STATUS" \
  --arg gateEAuxiliaryExitCode "$GATE_E_RUNNER_EXIT_CODE" \
  '{schema:$schema,run:{startedAt:$runStartedAt,durationSeconds:($runDurationSeconds|tonumber)},
    results:{aoRealProofConformance:$aoConformanceStatus,gateEAuxiliary:$gateEAuxiliaryStatus,
      gateEAuxiliaryExitCode:($gateEAuxiliaryExitCode|tonumber)},
    operationContext:{chainInstanceGenesisRoot:$route[0].chainInstanceGenesisRoot,
      preparedOperationRoot:$route[0].preparedOperationRoot,operationId:$route[0].operationId,
      configurationRoot:$route[0].configurationRoot,configurationEpoch:$route[0].configurationEpoch,
      verifierId:$route[0].sourceVerifierId,programVKey:$route[0].programVKey,programElfSha256:$route[0].programElfSha256,
      operationEnvelopeRoot:$route[0].operationEnvelopeRoot,operationCalldataHash:$route[0].operationCalldataHash,
      operationTarget:$route[0].operationTarget,operationSelector:$route[0].operationSelector,
      operationPrincipal:$route[0].operationPrincipal,operationNonce:$route[0].operationNonce,
      assurancePolicyRoot:$route[0].assurancePolicyRoot,consumedRegistrationRoot:$route[0].consumedRegistrationRoot,
      consumedRegistrationRevision:$route[0].consumedRegistrationRevision,
      consumedProjectionDefinitionRoot:$route[0].consumedProjectionDefinitionRoot,
      consumedProjectionResultRoot:$route[0].consumedProjectionResultRoot},
    artifacts:{deploymentManifestSha256:$deploymentManifestSha256,proverInputSha256:$proverInputSha256,
      proofFixtureSha256:$proofFixtureSha256,aoRouteManifestSha256:$aoRouteManifestSha256,
      sourceStateSha256:$sourceStateSha256,aoPreSourceInventorySha256:$aoPreSourceInventorySha256,
      aoPostSourceInventorySha256:$aoPostSourceInventorySha256,prfPreSourceInventorySha256:$prfPreSourceInventorySha256,
      prfPostSourceInventorySha256:$prfPostSourceInventorySha256,runLogSha256:$runLogSha256}}' \
  > "$EVIDENCE_MANIFEST"

echo "Fresh deployment manifest, prover input, proof fixture, AO route manifest, final evidence manifest, source state, and run log retained in: $ARTIFACT_DIR"
