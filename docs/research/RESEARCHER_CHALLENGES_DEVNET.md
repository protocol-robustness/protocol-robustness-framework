# Researcher Challenge local devnet

The local devnet provides HTTP transport for the existing signed challenge
actions. It does not change the action, replay, reward-obligation or
Challenge 001 arithmetic contracts. The service has no signing keys: researchers
and reviewers sign actions on their own machines, and the service verifies and
appends them through the existing file-admission path.

## Start a complete local demonstration

From the repository root, create a fresh temporary reviewer key and bundle.
Keep this private key out of the repository. The generated challenge uses a
local-devnet scope, `TEST-POINTS`, a 100-point demonstration quote, capacity 2,
and an empty history. Its profile is derived using a synthetic bounded
shortfall scenario that differs from the published calibration inputs. The
bundle now includes that canonical comparison and a matching replayable
`research-input.edn`, with the evidence map linking the comparison root to its
full results. These are synthetic local fixtures, not an independent researcher
finding. The bundle is marked test-only and carries no approval, funding,
publication or activation authority.
The generated `demo-status.edn` records the configuration, profile, challenge,
candidate, source-manifest and comparison roots alongside explicit false values
for funding, publication and activation authorization.

```sh
umask 077
challenge_demo_dir="$(mktemp -d)"
openssl genpkey -algorithm ED25519 \
  -out "$challenge_demo_dir/reviewer-private.pem"
openssl pkey -in "$challenge_demo_dir/reviewer-private.pem" -pubout \
  -out "$challenge_demo_dir/reviewer-public.pem"
clojure -M -m resolver-sim.research.researcher-challenges-devnet-demo \
  "$challenge_demo_dir/reviewer-public.pem" "$challenge_demo_dir/artifacts"
clojure -M -m resolver-sim.research.researcher-challenges-devnet \
  "$challenge_demo_dir/artifacts/configuration.edn" \
  "$challenge_demo_dir/artifacts/history.edn" \
  "$challenge_demo_dir/artifacts/profile.edn" \
  "$challenge_demo_dir/artifacts/evidence.edn" 8787
```

The server prints the configuration root and listens on `127.0.0.1:8787`.
The reviewer private key is available only in the temporary directory for
local action signing. Remove the directory when the demonstration is over.
For an offline testnet-scope rehearsal, append `testnet` to the generator
command and use a fresh output directory; this only changes the signed protocol
scope and does not make the local service public.

## Local smoke-run evidence

The 2026-11-18 smoke run recorded the following artifact roots and reported
matching HTTP health and checkpoint responses. Its temporary bundle and server
logs were removed. The historical reviewer key is not available in the current
environment, so that key attribution and the old HTTP responses cannot now be
independently reproduced. Treat the old smoke run as historical
operator-reported evidence.

A fresh bundle was generated on 2026-11-19 using the available development
reviewer public key at `/home/user/Code/challenge-001-keys/challenge-001-reviewer.pub.pem`.
It remains test-only. Generation produced:

| Fresh bundle artifact | Root |
| --- | --- |
| Configuration | `sha256:65f2f81203a2bc171ad9a7d00f955b7aa6881edd93fc20a33ad27b3e3a1e5cb0` |
| Profile | `sha256:ba83c20dd164886aca6446f07e4b38e4da6d746baee4325684eac4427901d438` |
| Research input | `sha256:d95765d942e45f9f91983fab4002599678f09547b31c4e81f3e57bcdc0cce7e2` |
| Challenge | `sha256:2d63d9ebc44783cbad4238116f701107ce9280bfd6b26e8e242a139741410b42` |
| Candidate | `sha256:94a974fd5c2d30c2574cd82b6159b147812fe21788b07ed21f4212a9ac794f73` |
| Comparison | `sha256:75269ff3f197a741d06d7a68cf9b3bb741075fca036e357dc18d438da0386a6d` |
| Empty history checkpoint | `sha256:1272a89965c4a802d844b34ca59f7b06b84e87501f87f747be201eac0334a24e` |

The generated bundle was replayed independently by
`shortfall-pilot-cli replay` with exit code 0. The bundle files and their
SHA-256 manifest are retained in
[`evidence/researcher-challenges/local-devnet-bundle-20261119`](../../evidence/researcher-challenges/local-devnet-bundle-20261119).
The service started at `127.0.0.1:18788`; `/healthz` and `/v1/checkpoint`
reported matching configuration, network and empty-history roots. The process
was stopped after the checks. This verifies a fresh local HTTP smoke run, not a
public service or testnet deployment. The retained offline testnet-scope
rehearsal below contains a separate four-action service-health check.

The historical 2026-11-18 artifact roots were:

| Artifact | Root |
| --- | --- |
| Configuration | `sha256:a0f54bc2144ce1a02ccdbc362cabb59eb4b27bb44504bb1733ee42f915f2999d` |
| Challenge | `sha256:2d63d9ebc44783cbad4238116f701107ce9280bfd6b26e8e242a139741410b42` |
| Profile | `sha256:3aa5bf5052c8249c0fd9245e41050ca3085bea201800f6928267ab49bbec1462` |
| Candidate | `sha256:94a974fd5c2d30c2574cd82b6159b147812fe21788b07ed21f4212a9ac794f73` |
| Comparison | `sha256:75269ff3f197a741d06d7a68cf9b3bb741075fca036e357dc18d438da0386a6d` |
| Empty history checkpoint | `sha256:c34917f2edf9f851136cabceacadabb9077f3d56434a94319ea998abfacebaeb` |

The temporary bundle and server process were removed after this smoke run. It
proves local startup and empty-history readiness only; it does not qualify a
research submission, external host, testnet deployment, funding or payout.

The same configuration/profile/history/evidence were also passed through the
offline signed-publication and retained-reader functions using the dev-only
operator key. The publication root was
`sha256:f6c570b208c88c070657eba2239e1c0a35c0dd75f75bef3b0f60481891e0aea4`;
first reader advancement changed state, exact retry was a no-op, and the
retained publication root matched. The publication and reader state were
temporary and removed. The operator key and origin were supplied by this local
demo, so this checks artifact replay and idempotency, not independent trust
bootstrap, remote availability, or latestness.

## Local offline testnet-scope rehearsal (2026-11-18)

The demo bundle generator accepts an optional final `testnet` argument. This
sets the signed challenge configuration namespace to `:testnet/challenge-001-demo`;
it does not publish the configuration, expose a service, or connect to a public
chain. The resulting test-only bundle was exercised through researcher
enrollment, task claim, replay-validated submission and a simulated reviewer
acceptance using temporary development keys. The acceptance derived a reward
obligation. The reviewer action is a local simulation, not an independent human
review.

The public artifacts and replay report are retained in
[`evidence/researcher-challenges/testnet-scope-rehearsal-20261118`](../../evidence/researcher-challenges/testnet-scope-rehearsal-20261118).
The checkpoint reports four admitted actions. Independent CLI replay exited
successfully against the retained configuration, profile, history and evidence.
The loopback service also started on `127.0.0.1:18787` using this exact bundle;
`GET /healthz` and `GET /v1/checkpoint` returned matching configuration,
network and four-action history roots. The service was stopped after the
queries. Its runtime correctly reports `:local-devnet`; the `:testnet` value is
only the signed protocol namespace.

| Artifact | Root |
| --- | --- |
| Configuration | `sha256:0aecdbac6116c3fea6b85ad07e808a4775ce0f40b7bda6913faab87f4aab44fc` |
| Network | `sha256:c1021f3b6da1e30fe9a06efe4ad48d441e99d9df46575403e0e1f327d42ce4e2` |
| Challenge | `sha256:2d63d9ebc44783cbad4238116f701107ce9280bfd6b26e8e242a139741410b42` |
| Submission | `sha256:3953d666db511d75806b7a70da579b683d0e2924d487920ee946c5e3bb5b1f19` |
| Simulated review action | `sha256:9032b5c0bd323d8dd0d17b7ea85bc3f5aba010f4d0b808d0de4d8f02cfc6a2e7` |
| Derived obligation | `sha256:3b7b1924387174b9e3a8a1e6cc15c5d39a64b169261dac353a90907a50a895b4` |
| Four-action history | `sha256:1922f3e569d9e7669c5bacde5eeeb78ec3607852d807182bda218b74c75807c3` |

This qualifies the offline signed-file protocol under a testnet scope only. It
does not establish distinct people or hosts, trusted publication/latestness,
public network transport, real review, authorized backing, testnet EVM
deployment or settlement, or mainnet readiness. The artifacts keep publication,
activation and funding authorization false.

## Use an existing prepared bundle

Prepare the public configuration, Challenge 001 profile and evidence map using
the existing commands in [Researcher Challenges V0](RESEARCHER_CHALLENGES_V0.md)
and [Researcher Network Pilot V0](RESEARCHER_NETWORK_PILOT_V0.md). Keep the
history on a local filesystem with atomic rename support. For a local-only run,
use a test currency such as `TEST-POINTS`, test-only keys and a fresh history
file. Do not use the proposed USDC reward values as funding authorization.

From the repository root:

```sh
clojure -M -m resolver-sim.research.researcher-challenges-devnet \
  configuration.edn history.edn challenge-001-profile.edn evidence.edn 8787
```

The optional control-policy file follows the port argument. The server binds to
`127.0.0.1` by default and accepts only the literal loopback addresses
`127.0.0.1` and `::1`. It rejects configurations outside a devnet/testnet scope
and requires a reward currency prefixed with `TEST-`. Omit the port to let the
operating system select an available local port. Stop with Ctrl-C.

## Local ERC-20 payout demonstration

For a full local payout run, start with a development-only configuration and
history containing one enrolled researcher whose submission has been accepted
and marked incentive-eligible. Obtain the exact challenge and replay-derived
reward-obligation roots from that history. Set `CHALLENGE_KEY_DIR` to the
directory holding the development-only operator and reviewer PEM files (for
the current local environment: `/home/user/Code/challenge-001-keys`). Then run
the Anvil composition:

```sh
python3 examples/assured-operations/scripts/demo_researcher_challenge_erc20.py \
  configuration.edn profile.edn history.edn evidence.edn \
  sha256:CHALLENGE_ROOT sha256:REWARD_OBLIGATION_ROOT \
  researcher.pub.pem researcher-private.pem \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem" \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pem" \
  "$CHALLENGE_KEY_DIR/challenge-001-reviewer.pem" \
  /tmp/researcher-erc20-demo-output --keep-chain
```

The script deploys the minimal fixed-supply `ResearcherDemoToken` and
`BountySettlement` contracts to a fresh loopback-only Anvil chain, funds the
token reserve, signs a demo-only payout policy and researcher consent, submits
the derived award and claim transactions using Anvil's unlocked development
accounts, and invokes the challenge transfer verifier. The output directory must
not already exist. It contains the public policy, consent, RPC context, transfer
reference and CLI report; it never copies private keys. `--keep-chain` leaves
Anvil running so the retained transfer inputs can be independently replayed
against the same local RPC. Before starting Anvil, the runner replays the whole
history through the Challenge 001 profile adapter and checks that the selected
researcher owns the one accepted, incentive-eligible obligation. Stop the script
with Ctrl-C to terminate the node.

The policy uses a synthetic backing-root commitment, a 1:1 reward-unit to token
base-unit conversion and Anvil's `included` head. This demonstrates the local
obligation-to-token path only. It does not verify real backing or qualify a
public testnet. Never deploy `ResearcherDemoToken` or reuse this policy on a
public network.

A completed local run (2026-11-18) is retained in
[`evidence/researcher-challenges/local-anvil-payout-20261118`](../../evidence/researcher-challenges/local-anvil-payout-20261118).
Its verifier report records `:valid? true`, the exact beneficiary and 100-token
amount, the settlement sender, reserve source state and included receipt. The
Anvil process was stopped after the run, so the retained report and receipts are
not a live RPC source for later independent re-query. The bundle marks backing,
funding, publication and activation as demo-only/unauthorized.

To prepare that local history from a generated bundle, enroll a development
researcher, claim a task slot, and submit the generated research input using the
commands in [Researcher Challenges V0](RESEARCHER_CHALLENGES_V0.md). The bundle's
`demo-status.edn` prints the challenge root; `research-input.edn` and
`evidence.edn` contain the synthetic, replayable arithmetic result. Before
review, validate the retained submission with:

```sh
clojure -M -m resolver-sim.research.shortfall-pilot-cli replay \
  configuration.edn profile.edn history.edn evidence.edn
```

The reviewer must inspect the comparison and make an explicit accept/reject and
incentive-eligibility decision under the review process in Researcher Challenges
V0. The demo does not create that review or obligation automatically. Use the
resulting accepted history and exact obligation root with the Anvil runner above.

## API

Requests and responses use one EDN value with content type
`application/edn`.

| Method and path | Purpose |
| --- | --- |
| `GET /healthz` | Runtime environment, configured network ID, readiness, configuration/network roots and current history root/count |
| `GET /v1/configuration` | Read the frozen public configuration |
| `GET /v1/history` | Read the complete signed local history |
| `GET /v1/checkpoint` | Derive the replayed history checkpoint |
| `POST /v1/actions` | Submit one already-signed action envelope |

The service caps action bodies at 1 MiB, applies a local per-address request
limit, serializes concurrent writes, and reuses the CLI's OS-locked,
file-forced, atomic history replacement. It validates the complete replay and
Challenge 001 arithmetic profile before replacing history. A stale prefix,
invalid signature, duplicate action, or out-of-bounds submission leaves the
history unchanged. Reads are not authenticated; the complete history may
contain sensitive evidence, so keep this process and its data on the local
machine.

## Qualification boundary

This is a local devnet transport check, not testnet readiness. It has no TLS,
public ingress, production rate limiting, distributed storage, multi-host
writer coordination, service identity, backups, online publication/latestness
service, reviewer key recovery, or payment execution. The local limiter and
1 MiB request cap are resource bounds for a single loopback process, not
denial-of-service protection. The history is still a single file-backed
authority, and the challenge server does not execute payout transactions.

The V0 `:payout` action is only a reviewer-signed status record containing an
external reference. The standalone
[`researcher-challenge-payee`](../../src/resolver_sim/research/researcher_challenge_payee.clj)
module can build and verify a researcher-signed recipient authorization bound
to one replay-derived eligible obligation, reward amount, chain ID and
instance root, asset ID and base-unit amount, and beneficiary address.
Verification remains possible after a payout status is
appended, while creating a new authorization after any payout record is
rejected. Consent is retained as a separate artifact rather than stored in V0
history; the transfer verifier consumes it alongside existing history.
Consent proves control of the enrolled challenge key's signature; it does not
prove ownership or control of the EVM address. The reward currency is still a
label, not an asset identifier, and `:recorded-paid` must never be presented
as proof of payment by itself. The ERC-20 adapter below now supports a
development-only dual-signed asset/conversion policy, researcher consent,
replay-derived approval calldata, on-chain duplicate-payable protection and a
read-only transfer verifier. Testnet qualification still needs authorized
policy review, independent backing verification, a deployed and funded contract,
actual approval and claim execution, cross-host verification, operational
reorg/finality procedures and independent review. The configured JSON-RPC
sources are trust inputs, not consensus light clients.

The consent artifact can be prepared and checked offline. First create a
dual-signed payout policy with the operator and reviewer keys. For this
development flow, both must be development keys and `:policy/demo-only?` must
be `true`; this is not a production authorization. The policy pins the exact
challenge, chain instance and genesis, ERC-20 contract/runtime digest,
decimals, exact rational conversion, payout contract/runtime digest, finality
requirement, PRO-80/81 backing root and contract token-reserve source root. The
backing root remains a commitment until it has
been independently verified by the existing backing process. The private key
paths are used only while signing and are never serialized. Output files are
created exclusively, so an existing artifact is not overwritten. Ensure
`CHALLENGE_KEY_DIR` is set in the shell that runs these commands.

```sh
clojure -M -m resolver-sim.research.researcher-challenge-payout-policy-cli build \
  configuration.edn payout-policy-request.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem" \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pem" \
  "$CHALLENGE_KEY_DIR/challenge-001-reviewer.pem" payout-policy.edn

clojure -M -m resolver-sim.research.researcher-challenge-payout-policy-cli verify \
  configuration.edn payout-policy.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem"

clojure -M -m resolver-sim.research.researcher-challenge-payee-cli build \
  configuration.edn history.edn payout-policy.edn payee-request.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem" researcher-private.pem \
  payee-authorization.edn

clojure -M -m resolver-sim.research.researcher-challenge-payee-cli verify \
  configuration.edn history.edn payout-policy.edn payee-authorization.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem"

clojure -M -m resolver-sim.research.researcher-challenge-payout-approval-cli prepare \
  configuration.edn history.edn payout-policy.edn payee-authorization.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem"
```

`payee-request.edn` supplies only `:obligation-root`,
`:beneficiary-address` and a researcher-selected `:nonce`. The payout policy
request supplies `:challenge/root`, `:chain/id`, `:chain/instance-root`,
`:chain/genesis-hash`, `:asset/id`, `:asset/decimals`, `:asset/code-sha256`,
`:asset/amount-per-reward-unit` as `{:numerator N :denominator D}`,
`:payout/sender` (the deployed `BountySettlement` contract),
`:payout/executor-code-sha256`, `:payout/required-finality`,
`:funding/backing-root`, `:funding/source-root` (the `TokenReserveFunded`
event's bytes32 source root) and `:policy/demo-only?`. Chain and token amounts are derived from the verified
policy and replay-derived obligation. EVM asset, sender and beneficiary
addresses may use checksum case in the input; the signed artifacts normalize
them to lowercase.

The prepare command prints arguments derived from the replayed accepted
obligation and signed researcher consent for BountySettlement.approveTokenBounty.
The contract's payable ID is the obligation root, so it rejects duplicate
approvals; the beneficiary later calls claim. The transaction sender must be
the EVM address configured as the contract's award authority, with testnet gas.
Keep that EVM key separate from the Ed25519 challenge keys. The command prepares
arguments only; it does not sign or submit an EVM transaction.

After independently checking the printed arguments against the policy and
consent, generate and submit the approval using the contract's configured
award-authority EVM account. For a testnet demonstration, import dev-only EVM
keys interactively into Foundry's encrypted local keystore; do not put raw keys
in shell variables, command arguments, source files or this repository. Confirm
the listed addresses match the configured funding authority, award authority,
and signed beneficiary before sending transactions. The `ResearcherDemoToken`
fixture is local-only and must not be deployed publicly.

```sh
cast wallet import --interactive challenge-testnet-funder
cast wallet import --interactive challenge-testnet-award-authority
cast wallet import --interactive challenge-testnet-beneficiary
cast wallet list
```

### Deploy and fund the payout example on a testnet

This is a public-chain development demonstration, not challenge activation. First
use a challenge configuration and network manifest that are already bound to
the selected testnet, chain ID, and genesis hash. Confirm those values through
the reviewed network configuration; do not derive a new `:chain/instance-root`
from an RPC response alone. Set `TESTNET_RPC_URL` to an HTTPS endpoint and use
dev-only Foundry keystore accounts funded with testnet gas. Never deploy the
local-only `ResearcherDemoToken` here. Use a known testnet ERC-20 address whose
runtime, decimals, and test-token provenance have been independently checked.

Deploy `BountySettlement` from the funding account. Set the two non-secret
addresses to the accounts imported above:

```sh
export FUNDER_ADDRESS=0x0000000000000000000000000000000000000000
export AWARD_AUTHORITY_ADDRESS=0x0000000000000000000000000000000000000000

forge create src/BountySettlement.sol:BountySettlement \
  --root examples/assured-operations \
  --broadcast \
  --rpc-url "$TESTNET_RPC_URL" \
  --account challenge-testnet-funder \
  --constructor-args "$FUNDER_ADDRESS" "$AWARD_AUTHORITY_ADDRESS"
```

Record the deployment address printed by `forge create` as `PAYOUT_CONTRACT`,
then verify the deployed runtime and immutable authorities against the expected
accounts before funding:

```sh
cast code "$PAYOUT_CONTRACT" --rpc-url "$TESTNET_RPC_URL"
cast call "$PAYOUT_CONTRACT" 'fundingAuthority()(address)' --rpc-url "$TESTNET_RPC_URL"
cast call "$PAYOUT_CONTRACT" 'awardAuthority()(address)' --rpc-url "$TESTNET_RPC_URL"
```

Use only a test ERC-20 actually deployed on that testnet. Confirm that the
funder has enough of it and enough testnet gas. `FUNDING_AMOUNT` is in token
base units. Approve and deposit the reserve, then retain the transaction hash
and receipt:

```sh
export TESTNET_TOKEN_ADDRESS=0x0000000000000000000000000000000000000000
export FUNDING_AMOUNT=1000000000000000000

cast code "$TESTNET_TOKEN_ADDRESS" --rpc-url "$TESTNET_RPC_URL"
cast call "$TESTNET_TOKEN_ADDRESS" 'decimals()(uint8)' --rpc-url "$TESTNET_RPC_URL"
cast send "$TESTNET_TOKEN_ADDRESS" 'approve(address,uint256)' \
  "$PAYOUT_CONTRACT" "$FUNDING_AMOUNT" \
  --rpc-url "$TESTNET_RPC_URL" \
  --account challenge-testnet-funder
cast send "$PAYOUT_CONTRACT" 'fundTokenReserve(address,uint256)' \
  "$TESTNET_TOKEN_ADDRESS" "$FUNDING_AMOUNT" \
  --rpc-url "$TESTNET_RPC_URL" \
  --account challenge-testnet-funder
```

From the successful reserve transaction receipt, retain the
`TokenReserveFunded` event's indexed `sourceRoot` (`topics[1]`), token, funder,
amount, sequence, and block identity. Put that exact source root into the payout
policy request. Compute runtime SHA-256 digests from the bytecode bytes rather
than the hexadecimal text:

```sh
TESTNET_TOKEN_CODE_SHA256=$(cast code "$TESTNET_TOKEN_ADDRESS" \
  --rpc-url "$TESTNET_RPC_URL" | sed 's/^0x//' | xxd -r -p | sha256sum | awk '{print "sha256:" $1}')
PAYOUT_CODE_SHA256=$(cast code "$PAYOUT_CONTRACT" \
  --rpc-url "$TESTNET_RPC_URL" | sed 's/^0x//' | xxd -r -p | sha256sum | awk '{print "sha256:" $1}')
```

Use the chain ID and genesis hash from the selected testnet. Bind these values
to the reviewed configuration and verified backing evidence before signing the
demo payout policy. A funding transaction and
on-chain reserve establish test-token custody only; they do not establish
backing for a real reward obligation or authorize the challenge.

Use the named keystore account for signing:

```sh
APPROVAL_CALLDATA=$(clojure -M -m resolver-sim.research.researcher-challenge-payout-approval-cli calldata \
  configuration.edn history.edn payout-policy.edn payee-authorization.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem")

cast send "$PAYOUT_CONTRACT" "$APPROVAL_CALLDATA" \
  --rpc-url "$TESTNET_RPC_URL" \
  --account challenge-testnet-award-authority
```

After the approval is finalized, the researcher claims with the EVM key whose
address is the signed beneficiary. Convert the sha256 obligation root to its
32-byte payload for PAYABLE_ID:

```sh
PAYABLE_ID="0x${OBLIGATION_ROOT#sha256:}"
cast send "$PAYOUT_CONTRACT" "claim(bytes32)" "$PAYABLE_ID" \
  --rpc-url "$TESTNET_RPC_URL" \
  --account challenge-testnet-beneficiary
```

After the transfer, re-run the verifier against the transaction. It produces a
recomputable report, not a new settlement artifact or ledger. The
`rpc-context.edn` sender must be the `BountySettlement` contract authorized by policy; the
token code digest and chain/genesis identity must come from the reviewed testnet
deployment. The `transfer.edn` file identifies a transaction receipt, block and
log index. The RPC context keys are `:chain-id`, `:chain-instance-root`,
`:genesis-hash`, `:token-contract`, `:token-code-sha256`, `:asset-decimals`,
`:sender`, `:beneficiary`, `:amount`, and `:required-finality` (`:included` or
`:finalized`); `:max-ancestry-depth` is optional and defaults to 512. The
transfer map keys are `:transaction-hash`, `:block-number`, `:block-hash`, and
`:log-index`. The beneficiary and amount must exactly match the signed consent.

```sh
clojure -M -m resolver-sim.research.researcher-challenge-transfer-cli verify \
  configuration.edn history.edn payee-authorization.edn payout-policy.edn \
  "$CHALLENGE_KEY_DIR/challenge-001-operator.pub.pem" \
  rpc-context.edn transfer.edn \
  "$TESTNET_RPC_URL"
```

The verifier confirms a successful receipt, exact ERC-20 `Transfer` and
`BountyTokenPaid` logs, matching obligation and consent roots, funding source,
the reserve source's on-chain token, funder, sequence and remaining/original
amounts at the payout block, settled payable state, beneficiary, amount, token
and executor runtime digests, and on-chain `decimals()` value,
canonical block, ancestry to the
configured genesis and the selected `latest` or `finalized` head. Run it against
an independently operated RPC source and again after the reviewer appends the
existing V0 `:payout` record with `:external/reference` set to the exact
transaction hash. Existing paid history records for that obligation must all
refer to that same transaction, and the transaction must not be referenced by a
different reward obligation. The report is not independently signed chain
evidence; each consumer must rerun the verifier from retained inputs. The
`BountySettlement` contract uses the reward obligation root as its payable ID
and rejects duplicate approvals/claims. The verifier is read-only and does not
submit the funding, approval or claim transactions. The RPC client
requires HTTPS for remote endpoints; cleartext HTTP is allowed only on
loopback for local development.

### Base Sepolia preflight (development only)

Base Sepolia is the selected EVM testnet for the current demonstration because
the proposed mainnet asset scope is Base USDC. The testnet must use a separate
configuration, signed test reward policy, and explicit test currency; do not
reuse the Base mainnet approval payload. Base documents chain ID `84532` and
the public RPC `https://sepolia.base.org` ([network connection
guide](https://docs.base.org/get-started/connect-to-base), [chain ID
reference](https://docs.base.org/base-chain/api-reference/ethereum-json-rpc-api/eth_chainId)).
The public RPC is suitable for preflight only; the payout verifier also reads
block zero and therefore needs an archive-capable HTTPS RPC.

The 2026-11-19 live preflight is retained in
[`evidence/researcher-challenges/base-sepolia-preflight-20261119`](../../evidence/researcher-challenges/base-sepolia-preflight-20261119).
All eight endpoints returned chain ID `0x14a34`. Base's public RPC,
PublicNode, BlockReq's public route, DRPC's public endpoint, Tenderly, Nodies,
and Pocket returned the Circle test USDC runtime digest
`sha256:e878b99fc0c17354c86c7edeb999c70c18cfd925abb0958496f027cbad996d28`
and decimals `6`; OnFinality rate-limited those token reads with HTTP 429. Base
and PublicNode rejected
`eth_getBlockByNumber("0x0", false)` because history before block 47,000,000
is pruned. BlockReq's unauthenticated route reported a 1,024-block history
limit, and DRPC returned `null` for block zero. Tenderly, OnFinality, Nodies,
and Pocket returned the genesis hash
`0x0dcc9e089e30b90ddfc55be9a37dd15bc551aeee999d2e2b51414c54eaf934e4`.
Nodies and Pocket also served the matching USDC runtime and decimals. Provider
documentation lists the
[OnFinality Base Sepolia endpoint](https://www.onfinality.io/en/rpc-assistant/base-public-rpc-url-and-dev-tools)
and [Pocket's Base Sepolia public endpoint](https://docs.pocket.network/developers/supported-chains/).
Treat that genesis value as a preflight observation only: pin it in a reviewed
network manifest and verify it through another independently operated,
archive-capable source before signing a payout policy. The verifier must still
be run against a second independent archive-capable RPC. Current evidence has
four public endpoints returning genesis; three of them also matched token code
and decimals. This establishes archive-read availability across those endpoints,
but does not by itself prove operational independence. That still needs review;
the transfer verifier must be run against two independently operated providers
after a funded deployment and actual test payout. The exact per-provider
responses and hashes are retained in the preflight JSON.

Circle lists Base Sepolia at its [testnet USDC faucet](https://faucet.circle.com/)
and states testnet tokens have no financial value; request test USDC only after
the test-only beneficiary/funder addresses are finalized. Obtain test ETH from
a Base Sepolia faucet for transaction gas. Faucet assets are test resources,
not backing, funding authorization, or reward value. Preserve faucet receipts
and observed balances in the demo evidence, and keep the publication,
activation, and real-funding flags false. Never send a faucet or testnet asset
to a mainnet address.

Three encrypted, development-only Foundry keystores have been created outside
the repository. Their exact local directory and password-file locations are
kept out of tracked files; set `TESTNET_KEY_DIR` and `TESTNET_PASSWORD_FILE`
locally to the protected files. The non-secret inventory is retained in the
preflight evidence directory. The current public role addresses are:

| Role | Base Sepolia address |
| --- | --- |
| Funder | `0xBE6f78F336A746451d1b9A1133BA6fEC1F1B8E2E` |
| Award authority | `0xdE4e014D59a0ec53d8A462c0dC4D7D7F1E92D1dA` |
| Beneficiary | `0xcAc39a6A4d05b99b747d2a6DE73552Fb3D7FE7b9` |

Use these exact non-secret settings when resuming the deployment. The funder,
award authority, and beneficiary each need Base Sepolia ETH for their own
transactions; the funder also needs test USDC. Do not use `--offline` for a
public-chain broadcast.

```sh
export TESTNET_RPC_URL=https://sepolia.base.org
export TESTNET_KEY_DIR=/path/to/dev-only-keystores
export TESTNET_PASSWORD_FILE=/path/to/dev-only-keystore-password
export FUNDER_ADDRESS=0xBE6f78F336A746451d1b9A1133BA6fEC1F1B8E2E
export AWARD_AUTHORITY_ADDRESS=0xdE4e014D59a0ec53d8A462c0dC4D7D7F1E92D1dA

forge create src/BountySettlement.sol:BountySettlement \
  --root examples/assured-operations \
  --broadcast --rpc-url "$TESTNET_RPC_URL" \
  --keystore "$TESTNET_KEY_DIR/challenge-testnet-funder" \
  --password-file "$TESTNET_PASSWORD_FILE" \
  --constructor-args "$FUNDER_ADDRESS" "$AWARD_AUTHORITY_ADDRESS"
```

When sending the reserve approval/deposit, pass the funder keystore and
password-file options. The award-authority approval and researcher claim
transactions use the corresponding award-authority and beneficiary keystore
files with the same `--password-file`. Check the `--from` address and chain ID
before every broadcast. Only the Circle testnet token listed in the preflight
may be used for this demonstration.

The earlier development addresses were retired after their protected keystore
location was unavailable in the current environment. A live balance check
found zero ETH on all six old and current addresses and zero Circle test USDC on
both funder addresses at the latest preflight. Do not request funds to the
retired addresses. Request Base Sepolia ETH from a faucet listed in
[Base's testnet funding guide](https://docs.base.org/get-started/get-funds)
and test USDC from the [Circle faucet](https://faucet.circle.com/) after
confirming the beneficiary and the small demonstration amount. The Circle
faucet is permissionless but requires its browser reCAPTCHA, so an operator
must submit that request interactively. Retain the faucet transaction hashes
and verify balances before deployment. Do not import these accounts into a
mainnet wallet or use them for mainnet signing.

Current state: the RPC and Circle token identity preflight passed, but no
testnet EVM accounts have been funded, no `BountySettlement` has been deployed,
a non-broadcast Foundry `forge create` dry-run against Base Sepolia passed for
the current funder and authority addresses (gas estimate `0x152b29`), and live
balance reads returned zero ETH for all active and retired role addresses and
zero test USDC for both funder addresses. No transaction was submitted, no
`BountySettlement` has been deployed, no reserve or award transactions have
been made, and no signed Base Sepolia challenge publication exists. Public
testnet payout and challenge activation remain unqualified until those steps,
cross-host replay, independent review, and durable key-recovery checks are
complete.

The repository also has a separate native-asset payout reference in
[`BountySettlement.sol`](../../examples/assured-operations/src/BountySettlement.sol)
and an Anvil-to-JSON-RPC qualification flow in
[`verify_bounty_rpc.py`](../../examples/assured-operations/scripts/verify_bounty_rpc.py),
backed by the [`rpc-observation` verifier](../../src/resolver_sim/economics/with_bounty/rpc_observation.clj)
and [`obligation-settlement`](../../src/resolver_sim/economics/with_bounty/obligation_settlement.clj).
That flow pays native currency and produces bounty-payable evidence; it remains
separate from researcher reward obligations and uses a configured RPC observer
rather than a consensus light client. The researcher ERC-20 adapter uses the
same `BountySettlement` example contract but has separate policy, consent,
approval-preparation and transfer-verification steps. Neither adapter supplies
independent consensus finality by itself.

Challenge activation remains separate: this server accepts the public
configuration supplied at startup; it does not approve, publish, or activate a
challenge. Testnet operation still needs an approved testnet configuration and
reward policy, signed publication/bootstrap, independent reader trust, deployed
test-token identity, externally reachable transport, operational key recovery,
cross-host replay, independent review and the verified testnet payout path above.
Mainnet deployment additionally needs owner authorization, reviewed source and
bounded funding, secure key custody, audited operations, a mainnet asset policy
and independent settlement verification. No mainnet deployment or transaction
is performed by this tool.
