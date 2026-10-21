# Base Sepolia testnet preparation checklist

**Scope: isolated public-testnet demonstration only. This is not a pre-mainnet
release, production readiness, or full PRO-95 qualification.** Testnet
transactions are public and persistent. Use a dedicated test-only wallet and
synthetic token; never use production keys, customer funds, USDC, or other real
assets.

This checklist records the exact inputs and evidence required before a Base
Sepolia transaction is prepared or submitted. Items marked **Owner decision**
must be supplied or approved by the project owner; the repository does not
infer them from placeholders, locally generated demo keys, or a passing local
test.

## 1. Owner decisions required before deployment preparation

Record these in an approved, versioned demo run plan:

| Decision | Required value / acceptance | Current state |
|---|---|---|
| Demonstration identity | Name, purpose, exact operation scenario, and explicit label `demonstration-only-not-pre-mainnet-release`. | Scope is Base Sepolia; exact owner-approved deployment identity is absent. |
| Source revision | PRF, PRF-EVM, coprocessor/prover, dependencies, Solidity compiler, optimizer and `via_ir` settings; clean/dirty state and content inventories. | Local candidate revisions exist; no testnet run source-state package exists. |
| Contract composition | Exact contracts to deploy and their constructor/configuration values: demo token, reserve, allocator, controller, assurance, ratification/admission, verifier adapter, verifier implementation, and any protected execution target. Mark omitted components and resulting claim limits. | No approved deployment composition. |
| Proof identity | Proof system, program ID, VKey, ELF digest, public-values schema and length, real verifier address/runtime identity, and a retained proof fixture. Do not substitute an accepting/mock verifier while claiming proof verification. | The Base Sepolia config has `TBD` values; not approved. |
| Demo asset | New synthetic test token only; owner-approved token name/symbol, decimals, mint authority, total mint cap, and the exact units used in the run. Do not use a real stablecoin. | No token policy or deployed token. |
| Demo policy and requests | Owner-approved synthetic requests, allocation rule/version, cutoff, expected allocation, restricted reserve, funding amount, unmet-request meaning, and any refund rule. | Current 50/30/20 fixture is illustrative only. |
| Beneficiaries | Test-only pseudonyms and controlled recipient addresses, with a dedicated way to sign/observe claims. The existing `0x111…`, `0x222…`, `0x333…` placeholders are not controlled recipients and must not receive funds. | No controlled recipient addresses or signed testnet beneficiary binding. |
| Authorities | Distinct selected source, policy, beneficiary, and funding authority public keys/roles/statuses; key custody and rotation/revocation rules; approval record binding the exact package digests. These may be test-only authorities, but must be clearly classified as such. | Existing demo keys were ephemeral and their private keys discarded; bundled public keys are not independent authorities. |
| Deployer and funder | Public address, role, custody type, testnet ETH budget, maximum number of transactions, and maximum gas/ETH spend approved for this run. Use a dedicated test-only signer. | No signer or gas budget configured. |
| Network trust | Base Sepolia chain ID `84532`; selected RPC endpoint(s); independent source for chain/header checks; exact freshness and reorg policy; selected L2 safe/finalized interpretation and corresponding Ethereum Sepolia L1 evidence requirements. | Public RPC candidate is documented, but not selected or probed; no header/finality trust policy. |
| Release boundary | Approved page/report wording for the demonstrated claims and explicit exclusions. | Must continue to say testnet demonstration only; not pre-mainnet release. |

Base documentation lists Base Sepolia as chain ID `84532` (`0x14a34`). The
documented public RPC candidate is `https://sepolia.base.org`; a response from
that endpoint is an observation source, not an independent trust anchor. See
[Base `eth_chainId` documentation](https://docs.base.org/base-chain/api-reference/ethereum-json-rpc-api/eth_chainId).

## 2. Replace the owner-approval-required configuration templates

Do not deploy using the current files unchanged:

- `etc/deployments/prf-base-sepolia/initial-configuration-package.edn`
- `etc/deployments/prf-base-sepolia/authority-state-package.edn`

They intentionally contain `TBD` fields, a placeholder principal, and
`:package/status :candidate-owner-approval-required`. A testnet preparation is
not ready until the owner supplies and approves all of the following, with
roots recomputed by the existing PRF derivation code:

1. Initial configuration: approved demo protocol genesis root; module registry
   profile/capabilities; evidence policy with actual proof ID, VKey, ELF
   digest, public-values schema/length and real verifier requirement; exact
   escrow template registry; test-token and entitlement descriptor roots;
   chain ID `84532`; governance and interoperability policies. Keep
   cross-domain/bridge behavior disabled unless explicitly in scope.
2. Authority state: the same approved demo protocol genesis root; chain ID
   `84532`; named test-only authority profile; actual owner-approved public
   principal address; initial epoch and decisions. Replace the placeholder
   principal.
3. Candidate package-set review: derive the full package set, record source
   revision/tool versions and content digests, and obtain an approval record
   whose subject is the exact derived manifest digest. A derived root alone is
   not approval.
4. Chain-instance genesis: after deployment addresses and runtime code hashes
   are known, derive the chain-instance identity from the actual chain ID,
   contract addresses/code hashes, approved authority-state root and
   configuration root. Validate the candidate with the existing canonical
   implementation; do not enter hand-chosen roots.
5. Deployment manifest: bind every contract address, runtime code hash,
   constructor/configuration value, deployer, chain ID, deployment transaction
   and receipt to the approved package set.

The current Base Sepolia templates do not define an approved production
protocol, authority, verifier or token configuration. Do not promote their
derived candidate roots to approved deployment identities.

## 3. Prepare keys, RPC and testnet funds locally

Before any signing or write request:

1. Configure the selected Base Sepolia RPC URL in a local ignored environment
   file or shell session, for example `BASE_SEPOLIA_RPC`. Never commit an RPC
   credential or secret. A public endpoint may require its own rate-limit
   policy; document the exact endpoint and provider used.
2. Create/import a dedicated test-only deployer/funder into an encrypted
   Foundry keystore. Do not put a private key in the repository, shell history,
   Linear, a report, or an agent prompt. Record only its public address and
   signer method. The project's local Gate E script has an Anvil private-key
   default and local-only roots; it must not be used unchanged for Base
   Sepolia.
3. Create dedicated controlled test recipient wallets. Keep their secrets
   outside the repository and record only pseudonyms and public addresses in
   the approved beneficiary authorization. Do not reuse placeholder addresses
   from the synthetic adapter package.
4. Obtain Base Sepolia ETH through a suitable testnet faucet or other owner
   approved source. Record source, receiving address, observed amount and
   transaction hash. Set a numeric upper bound for total ETH spend and per-
   transaction gas before use. Testnet ETH is not production value, but
   broadcasts still create public persistent records.
5. Run read-only preflight calls and retain their raw responses and UTC time:

   ```sh
   cast chain-id --rpc-url "$BASE_SEPOLIA_RPC"
   cast block-number --rpc-url "$BASE_SEPOLIA_RPC"
   cast balance "$DEPLOYER_ADDRESS" --rpc-url "$BASE_SEPOLIA_RPC"
   ```

   Confirm the chain ID is `84532`. Record RPC client/version and compare
   chain/header identity using the independently selected source. Stop if
   endpoints disagree, the signer address is unexpected, or the spend budget
   is not sufficient and approved. These checks are preflight only; they do
   not qualify chain consensus or finality.

## 4. Build and simulate without broadcasting

Before creating a transaction:

1. Build from the exact reviewed PRF-EVM source and lockfiles. Retain compiler,
   Forge, Rust/prover and dependency versions, build logs, source tree IDs,
   dirty/untracked status and SHA-256 inventories.
2. Run the existing offline tests and local real-proof qualification. Keep the
   synthetic Base Sepolia Stage B package separate from any deployment-bound
   source/proof package.
3. Create a dedicated Base Sepolia deployment script/configuration with no
   Anvil default private key, no localhost RPC fallback, no placeholder
   roots, no mock verifier, and no implicit `--broadcast`. Require explicit
   network, signer, approved package digests, and spend cap; default to
   simulation/dry-run.
4. Simulate the exact deployment and operation transactions against the
   selected network fork or RPC simulation path. Retain the unsigned/signed
   transaction plans, calldata, predicted addresses/nonces, estimated gas,
   expected events/state changes and dry-run output. A simulation is not a
   mined execution.
5. Have an operator review the final transaction plan and confirm the
   approved demo addresses, amount caps, target chain ID, code identity,
   proof identity, and recovery plan. Only then may the separately approved
   transaction set be submitted.

Do not run `examples/assured-operations/pro-rata-conformance/run-real-v2-sp1.sh`
for a public testnet deployment. That script currently defaults to a local
Anvil RPC and Anvil private key and exports synthetic roots. A separate,
reviewed testnet runner is required.

## 5. Public testnet demonstration transactions and evidence

The exact transaction sequence depends on the owner-approved contract
composition. At minimum, retain separate transaction evidence for each
performed action, as applicable:

1. Deploy the demo token and all selected PRF/EVM contracts/verifier modules.
2. Initialize the approved configuration/authority state and register the
   required proof verifier/program identity.
3. Mint/transfer only the approved synthetic token amount into the reserve.
4. Submit and admit the source-bound proof/statement. Record which source,
   policy, operation and reserve roots are directly encoded in its public
   values, and bind any other roots through their explicit signed or package
   links. Do not claim a direct binding where the schema does not encode one.
5. Ratify/fund and activate the operation through the selected protected
   route.
6. Submit each controlled beneficiary withdrawal; record any separately
   restricted-capital refund as a distinct action.
7. Exercise only approved negative cases (for example wrong proof, stale
   reserve, duplicate claim or wrong recipient), with expected revert/failure
   and no state/capital transition. Negative cases submitted onchain still
   consume testnet gas.
8. Read final balances, operation state and liabilities at a common recorded
   block; independently reconcile source units, restricted reserve, funded
   entitlements, payments, refunds, remaining liabilities and custody.

For every submitted action retain: raw signed transaction bytes; transaction
hash; sender; chain ID; nonce; target and calldata; receipt bytes/JSON and
status; transaction index; raw logs with emitter/topics/data/index; block
header RLP, hash, number and parent; pre/post relevant contract state and token
balances; deployed runtime bytecode and code hash; exact source, policy,
beneficiary, funding and proof/public-values roots; provider identity/version;
UTC capture time; and any failure/retry linkage. Preserve both failed
attempts and permitted retries. Do not rely on a Forge broadcast file alone
when actual RPC transaction/receipt linkage disagrees.

For the existing receipt inclusion verifier, each case must provide the exact
v1 case fields (`receipt`, `transactionRaw`, `transactionIndex`, transaction
and receipt MPT proof nodes, `blockHeaderRlp`, bounded `headerAncestry`, and
`expectedSender`) under
`prf-ethereum-receipt-inclusion-case.v1`. The trust context is v2 and its exact
SHA-256 must be pinned outside the case bundle. An ordinary RPC receipt is not
itself an inclusion proof; independently construct and check both transaction
and receipt trie paths against the header roots.

The current verifier proves inclusion and ancestry to the supplied pinned
header only. It does **not** authenticate the pin through Base consensus,
prove ancestry from genesis, or establish safe/finalized status.

## 6. Base L2 finality evidence and qualification boundary

Base's protocol documentation distinguishes unsafe, safe and finalized L2
heads. Safe L2 blocks are derived from the currently canonical L1 chain;
finalized L2 blocks are derived from finalized L1 data. A Base RPC label or
single provider response is not independent verification of either claim.
See the [Base derivation specification](https://docs.base.org/specifications/base-protocol/consensus/derivation).

For a testnet demonstration, choose and state one of these result classes:

- **Receipt observed:** provider returned a receipt; no independent inclusion
  or finality claim.
- **Receipt inclusion under external header pin:** existing verifier confirms
  the transaction and receipt MPT paths and ancestry to the pinned L2 header;
  still no consensus/finality claim.
- **Base safe/finalized status independently checked:** requires a separate
  Base L2-to-Ethereum-Sepolia-L1 evidence path, independently selected L1 and
  L2 trust sources, appropriate ancestry/derivation checks, a freshness/reorg
  policy, and an implementation specifically qualified for Base finality.

The current PRF receipt verifier does not implement the third class. Do not
label a Base testnet transaction finalized or describe PRO-95 Stage C as
qualified until that Base/L1 path has been independently implemented, reviewed
and exercised. A testnet demonstration result, even with receipt inclusion,
does not establish pre-mainnet readiness or mainnet assurance.

## 7. Completion checklist and handoff

Before marking the Base Sepolia demonstration run complete, the evidence bundle
must contain:

- owner-approved demo plan and transaction/ETH/token caps;
- approved initial configuration, authority state, package-set approval and
  chain-instance identity, all with recomputed roots and source identities;
- exact source/prover/proof/verifier/build identities and signed demo input
  package bound to the deployed chain instance and code hashes;
- encrypted local test signer and controlled recipient addresses (public
  addresses only in evidence);
- read-only RPC preflight, independent network/header source selection, and
  exact trust-context digest;
- dry-run plan, operator check, then individual raw transaction/receipt/log/
  header evidence for every deployment and operation;
- fresh-process receipt inclusion and accounting reconstruction, including
  failures/retries and final liabilities/refund conservation;
- explicit result classification, claim-by-claim scope, evidence links,
  verifier revisions, UTC times, source inventories, and open limitations;
- a visible statement: **“Base Sepolia demonstration only. Not a pre-mainnet
  release, production deployment, audit, or guarantee of safety.”**

If any item is missing, leave the corresponding result `unverified` or
`not-run`; do not fill it with an illustrative value or elevate the run's
qualification status.
