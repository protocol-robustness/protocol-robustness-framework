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

## Owner decisions recorded; actions still needed

The owner approved the proposed test-only recipient map, synthetic fixture,
deployer-relayed withdrawals, and bounded receipt-inclusion evidence class on
2026-10-23. See [`owner-decisions-20261023.edn`](./owner-decisions-20261023.edn).
That record reflects conversation approval; it is not a cryptographic owner
signature or independent authority attestation. The wallets remain pseudonymous
test recipients.

1. **Bind the approved recipient map into a Base-specific package.** Approved
   fixture recipients are
   listed in [`demo-recipient-wallets.json`](./demo-recipient-wallets.json):
   Alice `0x8bDd01C7DEad0AbE3e855b458B820D9d727bCCBF`, Bob `0x867Caab852f942Ff80B6409109F1DB76D4ecB36B`, and Carol
   `0xA57F019D83d1fbbc48500047bA27d21523fE55A8`. These addresses are backed by locally generated
   encrypted test keys (owner-only directory mode 0700, key files mode 0600),
   but establish no real-world claimant identity. The
   retained V3 proof and roots bind the fixture placeholder addresses and local
   chain ID `31337`; replacing them with these Base Sepolia addresses requires
   deriving new roots and regenerating the source witness/proof. Do not edit the
   old proof or substitute the addresses after proof generation.
2. **Use the approved relayer model.** The V3 allocator's `withdraw`
   entry point verifies the committed beneficiary opening and transfers to that
   beneficiary; it does not require `msg.sender` to be the beneficiary. A
   deployer will submit all three claims. Beneficiary gas funding is not
   planned. The recipient and deployer balances were zero in the retained
   read-only RPC observations.
3. **Use the approved synthetic demo profile.** It specifies 50/30/20 requests;
   80 synthetic token units
   deposited; 10 restricted and refundable to Carol; 70 allocatable and paid
   35/21/14; 30 unmet requests remain neither funded entitlements nor
   recognized liabilities. Token metadata, mint cap, and exact deployment
   composition still need to be set in the reviewed run package. The owner
   approved one dedicated test-only EOA for deployer, funder, configuration
   authority, and relayer roles. This is a deliberately centralized demo setup,
   not independent or production authority separation. The retained local
   fixture's `address(this)` authority is not an external authority approval.
4. **Approve the transaction and gas caps, then fund the deployer.** Deployer
   `0xdD82cCEB1D515F2e4fb0432fB5d53138813945D4` currently has zero Base Sepolia
   ETH. No safe exact amount can be set yet: there is no Base Sepolia deployment
   runner or dry-run transaction plan from which to estimate gas. I will produce
   the plan and maximum spend before any send. After that, obtain only the
   owner-approved amount of Base Sepolia ETH from a faucet or other approved
   testnet source and record its funding transaction. The Coinbase Developer
   Platform currently lists Base Sepolia among supported faucet networks
   ([faucet](https://www.coinbase.com/developer-platform/products/faucet)).
   The approved relayer model means the beneficiary wallets do not need gas.
5. **Review selected header observations and policy.** The approved bounded
   claim is `receipt_inclusion_verified_under_external_header_pin`, with Base
   safe/finalized status explicitly unclaimed. Base RPC and Blockscout returned
   the same hash for block 49259922; both observations are retained in
   `rpc-preflight-20261023.json`. Blockscout is a second-observation candidate,
   not yet established as an independent trust anchor. Independent review must
   assess source independence, ancestry/genesis binding, and freshness/reorg
   policy before any receipt claim is made. A safe/finalized claim would require
   an independently sourced Ethereum Sepolia L1 path and a qualified Base
   derivation/finality verifier, outside this bounded demo.
6. **Implement and review within the approved boundary.** The retained V3 run is a local
   Foundry test whose harness sets chain ID `31337` and uses test-only addresses.
   The related `prf-evm/script/DeployGateEEntitlementOptionA.s.sol` is explicitly
   local-only and uses a different fixed proof identity. Its program VKey/ELF
   constants do not match the V3 settlement manifest identity. No Base Sepolia V3
   deployment/operation runner exists in the inspected `prf-evm` snapshot
   (`0dad4c2790a60ecca9a41c5d518f99522eab94ce`). A separate, reviewed runner
   and Base Sepolia-bound proof package must be prepared; the local runner and
   proof cannot be broadcast unchanged.

The approved profile does not yet set a transaction plan, maximum transaction
count, or numeric ETH spend cap. No public transaction has been signed or
broadcast. These limits will be calculated from the reviewed dry-run plan.

## 1. Remaining configuration and qualification inputs

Record these in an approved, versioned demo run plan:

| Decision | Required value / acceptance | Current state |
|---|---|---|
| Demonstration identity | Name, purpose, exact operation scenario, and explicit label `demonstration-only-not-pre-mainnet-release`. | Scope is Base Sepolia; exact owner-approved deployment identity is absent. |
| Source revision | PRF, PRF-EVM, coprocessor/prover, dependencies, Solidity compiler, optimizer and `via_ir` settings; clean/dirty state and content inventories. | Local candidate revisions exist; no testnet run source-state package exists. |
| Contract composition | Exact contracts to deploy and their constructor/configuration values: demo token, reserve, allocator, controller, assurance, ratification/admission, verifier adapter, verifier implementation, and any protected execution target. Mark omitted components and resulting claim limits. | No approved deployment composition. |
| Proof identity | Proof system, program ID, VKey, ELF digest, public-values schema and length, real verifier address/runtime identity, and a retained proof fixture. Do not substitute an accepting/mock verifier while claiming proof verification. | The Base Sepolia config has `TBD` values; not approved. |
| Demo asset | New synthetic test token only; approved symbol/decimals and exact units, with a named mint authority and bounded mint cap. Do not use a real stablecoin. | `DEMO_UNIT`, zero decimals, an 80-unit supply cap and the test-only deployer as mint authority are approved; display name and deployment composition remain to be specified. |
| Demo policy and requests | Owner-approved synthetic requests, allocation rule/version, cutoff, expected allocation, restricted reserve, funding amount, unmet-request meaning, and any refund rule. | The synthetic 50/30/20 fixture and 35/21/14 allocation are approved in `owner-decisions-20261023.edn`; they are not yet bound to a Base Sepolia deployment. |
| Beneficiaries | Test-only pseudonyms and controlled recipient addresses, with a dedicated way to sign/observe claims. The existing `0x111…`, `0x222…`, `0x333…` placeholders are not controlled recipients and must not receive funds. | Recipient map approved in `owner-decisions-20261023.edn`; addresses are not yet bound into a Base Sepolia witness/proof. |
| Authorities | Record each test-only public key/role/status and custody boundary; do not imply independent authority separation. Bind run approvals to exact package digests. | One dedicated test-only EOA is approved for deployment, funding, configuration authority, and relayed withdrawals. Existing bundled Stage B Ed25519 keys are ephemeral demonstration keys and are not independent authorities. |
| Deployer and funder | Public address, role, custody type, testnet ETH budget, maximum number of transactions, and maximum gas/ETH spend approved for this run. Use a dedicated test-only signer. | Generated encrypted test-only signer: `0xdD82cCEB1D515F2e4fb0432fB5d53138813945D4`; approved for deployer, funder, configuration-authority, and relayer roles; unfunded, with no spend budget set. Keystore is outside the repository; passphrase is in the local system keyring. |
| Network trust | Base Sepolia chain ID `84532`; selected RPC endpoint(s); independent source for chain/header checks; exact freshness and reorg policy; selected L2 safe/finalized interpretation and corresponding Ethereum Sepolia L1 evidence requirements. | Owner approved receipt-inclusion verification with no finality claim. Base RPC reported chain ID `84532`, head `49259922` and hash `0x49a868fe73ae4775215dd0eef0b8a4a01e9be8d1c61c2c9badf68cf9bae5f37e`; Blockscout returned the same hash. Both observations are retained in `rpc-preflight-20261023.json`, but source independence, ancestry/genesis binding, and freshness/reorg policy require independent review before this header can be a trust pin. The generated signer balance was `0 wei` at block `49260186`; Base finality is out of scope. |
| Release boundary | Approved page/report wording for the demonstrated claims and explicit exclusions. | Approved scope remains testnet demonstration only; not a pre-mainnet release. |

Base documentation lists Base Sepolia as chain ID `84532` (`0x14a34`). The
documented public RPC candidate is `https://sepolia.base.org`; a response from
that endpoint is an observation source, not an independent trust anchor. See
[Base `eth_chainId` documentation](https://docs.base.org/base-chain/api-reference/ethereum-json-rpc-api/eth_chainId).

## 2. Replace the owner-approval-required configuration templates

Do not deploy using the current files unchanged:

- `etc/deployments/prf-base-sepolia/initial-configuration-package.edn`
- `etc/deployments/prf-base-sepolia/authority-state-package.edn`

They intentionally contain TBD fields, a placeholder principal, and
candidate-owner-approval-required status. For this demonstration, use a
separate owner-approved test-only configuration; do not copy the templates'
production genesis-root or authority-profile comments. The templates remain
unchanged. A testnet preparation is not ready until the owner supplies and
approves the demo-specific values below, with roots recomputed by the existing
PRF derivation code:

1. Initial configuration: newly derived demo-only protocol genesis root; module registry
   profile/capabilities; evidence policy with actual proof ID, VKey, ELF
   digest, public-values schema/length and real verifier requirement; exact
   escrow template registry; test-token and entitlement descriptor roots;
   chain ID `84532`; governance and interoperability policies. Keep
   cross-domain/bridge behavior disabled unless explicitly in scope.
2. Authority state: the same newly derived demo-only protocol genesis root; chain ID
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
   approved source. The Coinbase Developer Platform currently lists Base
   Sepolia as a faucet network
   ([faucet](https://www.coinbase.com/developer-platform/products/faucet)).
   Record source, receiving address, observed amount and transaction hash. Set a numeric upper bound for total ETH spend and per-
   transaction gas before use. Testnet ETH is not production value, but
   broadcasts still create public persistent records.
5. Run read-only preflight calls and retain the RPC results and UTC time:

   ```sh
   cast chain-id --rpc-url "$BASE_SEPOLIA_RPC"
   cast block-number --rpc-url "$BASE_SEPOLIA_RPC"
   cast balance "$DEPLOYER_ADDRESS" --rpc-url "$BASE_SEPOLIA_RPC"
   ```

   Confirm the chain ID is `84532`. Record RPC client/version and compare
   chain/header identity using the independently selected source. Stop if
   endpoints disagree, the signer address is unexpected, or the spend budget
   is not sufficient and approved. The initial read-only observation is
   retained in `rpc-preflight-20261023.json`; it records Foundry result strings,
   not raw HTTP envelopes. These checks are preflight only; they do not qualify
   chain consensus or finality.

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
