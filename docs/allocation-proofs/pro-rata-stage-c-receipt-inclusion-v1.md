# Pro-Rata Stage C receipt inclusion verifier

This is an optional Ethereum-family verifier for a retained transaction and
receipt. It implements the receipt-inclusion part of PRO-95 Stage C and reuses
the existing raw receipt records. It does not create another evidence graph.

Implementation:

- `scripts/forensic/ethereum_receipt_inclusion.py`
- `test/forensic_python/test_ethereum_receipt_inclusion.py`
- fixed synthetic fixture in `test/forensic_python/fixtures/pro95-stage-c-*`

## Checks performed

The verifier reconstructs the receipt payload from the RPC receipt fields,
including the typed-receipt prefix, logs, and logs bloom. It reconstructs the
signed transaction hash and sender for EIP-1559 type 2 and EIP-155 protected
legacy transactions. It rejects other transaction types until their signed
envelopes and sender-recovery rules are implemented and qualified.

It verifies both the transaction and receipt Merkle Patricia Trie paths keyed
by `RLP(transactionIndex)`. The raw signed transaction must be included in the
header's `transactionsRoot` at the same index whose receipt bytes are included
in `receiptsRoot`; the RPC receipt's transaction hash alone is not treated as
that binding. It then binds the header RLP hash and height to an externally
pinned trust-context file. This follows EIP-2718's transaction/receipt trie and
typed receipt encoding. EIP-1186 describes account and storage proofs; this
verifier does not treat those as transaction or receipt inclusion proofs.

The trust-context file pins the network family, chain ID, genesis hash, an
anchor header hash and height, source identity/revision, and freshness window.
The case may include bounded encoded header ancestry from the transaction's
block to that independently pinned anchor; every parent hash and consecutive
height is checked. The
caller must provide its exact SHA-256 from a verifier-selected source outside
the case bundle. A hash or signature carried only inside the bundle is not an
independent trust anchor.

The result status is deliberately scoped to
`receipt_inclusion_verified_under_external_header_pin`. It checks ancestry to
the pinned anchor, but does not establish that the anchor is canonical against
a consensus client or establish finality. The
trust context must set `finalityStatus` to `unverified`; the verifier fails
closed if asked to assert finality. The retained result includes the recovered
sender, transaction type, receipt status, trusted header source and explicit
limitations.

## OP Stack boundary

The input schema distinguishes `ethereum-l1` and `op-stack-l2`, but the verifier
does not implement an OP Stack finality adapter. Base's protocol documentation
distinguishes safe and finalized L2 heads and ties finalized L2 progress to L1
finality signals. It separately states that L1 withdrawal outputs require the
fault-proof challenge process. A trusted L2 receipt header alone therefore
cannot establish an L1 withdrawal's finality. A Base qualification must bind
the selected L2 head to independently trusted L1 ancestry/finality under an
explicit policy, and must keep L1 output finalization as a separate claim.

## Reproduction and current limit

Install `test/forensic_python/requirements.txt`, then run:

```sh
python3 -m pytest -q test/forensic_python/test_ethereum_receipt_inclusion.py
```

The fixed fixture is a small synthetic block with one signed EIP-1559
transaction and one receipt. Its trust-context digest is pinned in the test.
It exercises inclusion, sender recovery, receipt substitution, wrong index,
changed receipts root/header, stale or unknown trust context, chain replay,
type mismatch, and attempted finality promotion. It is an algorithm fixture,
not a public-chain block or independent chain trust source.

No public-chain transaction or RPC qualification was performed. To qualify the
retained Pro-Rata settlement, still supply a selected chain/network, a
verifier-selected independent header/finality source, exact real transaction
bytes and receipt proofs for each settlement action, and independently
authenticated source/authority records from Stage B. Reorg/replacement checks
must be rerun against that trust source. Stage C remains open until these
artifacts and a chain-specific finality policy are independently reviewed.

References:

- [EIP-2718: Typed Transaction Envelope](https://eips.ethereum.org/EIPS/eip-2718)
- [EIP-1186: `eth_getProof`](https://eips.ethereum.org/EIPS/eip-1186)
- [Base protocol derivation and finality](https://docs.base.org/specifications/base-protocol/consensus/derivation)
