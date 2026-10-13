# V3 source-commitment conformance vector

This is a separate, locally generated proof artifact for the V3 reserve-state
commitment branch. It reuses the controlled shortfall source witness and sets
`commitment-version` to `v3`. The reserve policy root is the test value
`0x1212…1212`; it was not obtained from an `OnchainShortfallReserveV3`
instance.

The SP1 guest output matched the Rust derivation, and both the SP1 SDK and the
EVM SP1 Groth16 verifier accepted the proof. A focused Foundry test also
confirms that modified proof material is rejected. The artifact is **not** V3
contract qualification: it has not been admitted under a registry or bound to
an actual V3 reserve policy root. See `manifest.json` for hashes and limits.

The V2 evidence files and manifest were left unchanged. A separate proof was
later generated against an actual local V3 reserve policy root; it passed SP1
SDK and EVM verifier checks but has not been admitted through the registry.
See [`../v3-live-context/`](../v3-live-context/README.md) for its evidence and
current qualification limits.
