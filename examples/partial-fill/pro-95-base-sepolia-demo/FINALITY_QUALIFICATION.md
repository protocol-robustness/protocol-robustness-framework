# Base Sepolia finality qualification boundary

**Classification: demonstration-only; no Base Sepolia finality qualification
has been performed. This is not a pre-mainnet release.**

This note defines which finality-related statements the PRO-95 demonstration
may make, what evidence each statement requires, and what the retained
receipt-inclusion verifier does not establish. It is a qualification plan,
not evidence that any listed gate has passed.

## Evidence classes

| Class | Permitted statement | Minimum evidence | Status |
|---|---|---|---|
| Observation | A named RPC returned a receipt or header at capture time. | Raw request/response, endpoint identity, client/version, UTC time, chain ID and queried block/hash. | Read-only observations only; no transaction receipt exists. |
| Receipt inclusion under external header pin | The raw transaction and receipt are included under the two roots in a supplied L2 header, and the encoded ancestry reaches a separately pinned header. | Raw signed transaction, receipt, both MPT paths, header RLP, ancestry, exact trust-context digest selected outside the case bundle, plus independent review of the pin source and freshness/reorg policy. | Verifier algorithm has a synthetic fixture; no public-chain case or independent pin has been qualified. |
| Base safe L2 status | The L2 block is represented as safe under a declared Base derivation and L1 trust policy. | Independently selected L2 and Ethereum Sepolia L1 sources; L1 canonical ancestry; Base derivation inputs and L1 origin binding; comparison against the node's safe head; capture-time and reorg policy; qualified verifier and retained outputs. | Not implemented or run for PRO-95. |
| Base finalized L2 status | The L2 block is derived from L1 data that the declared policy treats as finalized. | All safe-status inputs, plus independently checked Ethereum Sepolia consensus-finalized L1 ancestry and a qualified Base derivation/finality verifier binding the relevant L1 origin/data to the L2 block. Preserve the precise finality source, checkpoint, ancestry, verifier version and result. | Not implemented or run for PRO-95. |
| L1 withdrawal-output finalization | A specific Base withdrawal output has passed the applicable L1 proof/challenge process. | Separate withdrawal message/output proof, L1 inclusion and ancestry, applicable fault-proof/challenge-period state, and independently checked L1 finality under a qualified policy. | Out of scope. No L1 withdrawal is demonstrated. |

Base's protocol describes unsafe, safe and finalized L2 heads. In the stated
derivation model, safe L2 blocks have been derived from the currently canonical
L1 chain; finalized L2 blocks have been derived from finalized L1 data. These
are different claims from merely receiving a receipt from an RPC endpoint or
verifying its MPT paths. The selected implementation and current Base network
configuration must be assessed during qualification; this table does not
assert that the required adapter exists. See the [Base derivation specification](https://docs.base.org/base-chain/specs/protocol/consensus/derivation).

Finalized L2 inclusion and finalization of a Base-to-L1 withdrawal output are
also distinct. Do not use L2 `finalized` terminology to imply a withdrawal
output has completed its separate proof/challenge process.

## Current verifier boundary

`docs/allocation-proofs/pro-rata-stage-c-receipt-inclusion-v1.md` describes
the current verifier. It checks transaction and receipt trie inclusion under
one supplied header and checks encoded parent links from the transaction block
to an externally pinned anchor. The anchor's exact digest must be selected and
supplied outside the case bundle. The verifier does not authenticate that
anchor against Base consensus, establish ancestry from genesis, determine
whether the anchor is canonical, or compute Base safe/finalized status. It
rejects trust contexts that request a finality assertion. A matching block
hash from two RPC endpoints is an observation; it is not, by itself, proof of
provider independence or a consensus trust decision.

For the approved bounded PRO-95 demonstration, the maximum intended future
public-chain claim is receipt inclusion under an independently reviewed
external header pin. Base safe/finalized status is explicitly not claimed.
That bounded claim itself is not yet qualified because there are no deployment
transactions, receipts, transaction/receipt trie proofs, selected independent
header pin, or reviewed freshness/reorg policy. No L1 finality adapter should
be added merely to make the bounded demo appear complete.

## Required qualification sequence before any finality wording

1. Freeze the proposed claim at one row of the evidence table; record its
   explicit exclusions. For the current demo, use receipt inclusion only.
2. Name the chain and exact network. For a Base Sepolia claim, bind chain ID
   `84532`; never infer network identity from a display label.
3. Select trust sources and record why they are independent for the claim.
   Two endpoints with shared infrastructure or upstream data are not
   automatically independent.
4. Define block age, maximum observation lag, reorg response, anchor refresh,
   disagreement handling and stop conditions before collecting transaction
   evidence. A stale pin must fail closed; it must not be silently refreshed
   after seeing the desired result.
5. For inclusion, construct and verify both transaction and receipt MPT paths
   against the same exact header; verify sender, chain ID, index, receipt
   status and operation-specific logs/readbacks. Retain failed attempts and
   retries as separate linked evidence.
6. For safe/finalized L2 claims, additionally validate the Base derivation and
   corresponding Ethereum Sepolia L1 ancestry/finality with a separately
   reviewed verifier. Keep L1 withdrawal-output finalization as a different
   claim.
7. Have an independent reviewer reproduce the result from retained raw
   artifacts in a fresh process. Record reviewer, verifier/source versions,
   input digests, result digests, disagreements and limitations.

Until those gates pass, reports must say `finality: unverified` and
`public-chain qualification: not run`. Local Foundry execution, testnet
funding, matching RPC observations, a mined receipt, or an inclusion proof
alone does not upgrade this status.

## Latest read-only observation retained

Two read-only observations are retained in the `protocol-deployments`
repository. The token/source observation at block `50091313` has hash
`0x6f025c552b5df58108ae1bd753a511d3f87c0804005abcf948a2e0541816334c` and is
recorded at
`config/pro-95-base-sepolia-demo/observations/usdc-read-only-20261025.json`.
The later balance preflight selected block `50204763`, hash
`0x0472d6d8d50c6e5b6f9370340ec2c0a197c154df69165a0238a97bd387e2d4c8`; both
`https://sepolia.base.org` and `https://base-sepolia-rpc.publicnode.com`
returned that block hash and zero deployer ETH, zero payer USDC, and nonce
zero. Its raw requests and responses are retained at
`config/pro-95-base-sepolia-demo/observations/usdc-funding-preflight-20261025-50204763.json`
(SHA-256
`b9889d3d187a9a0d09d0a358261d76089138e698d0e611b1a4b76d1e825b90fc`).
Allowance was not observed. The selected block was below both endpoints' heads
at capture; this was a block-pinned snapshot, not a latest-head observation.
Neither artifact is an independently reviewed trust pin, finality evidence,
run-block observation, or proof of USDC transfer behavior.
