# Stablecoin privacy binding inventory

This is an implementation inventory, not an authority approval.

* Sensitivity classification and sink gates already exist in
  `resolver-sim.sensitivity.sentinel`: `classify`, `disclosure-allowed?`,
  `assert-export-allowed!`, and the signed remote decision verifier in
  `sentinel-client/verify-decision`. Remote signatures bind artifact hash,
  sink, request ID/hash and Sentinel policy hash. Preserve this contract.
* Sensitivity report provenance is built in
  `resolver-sim.sensitivity.report/build-sensitivity-report`; declared and
  structural classifications are covered in
  `docs/conceptual/sensitivity-provenance-chain.md`.
* `resolver-sim.configuration-head` derives a successor from a supplied
  transition and fences activation with expected head and authorized transition
  root. The component explicitly introduces no governance authority.
* `resolver-sim.risk.admission/resolve-current` resolves the current risk policy
  from retained V4 configuration/policy bodies and recomputes roots. It is
  risk-specific; do not add privacy semantics to risk-policy V4.
* `resolver-sim.genesis` validates chain configuration and transition artifacts.
  Genesis/configuration supplies initial state; later activation requires the
  existing control-plane authorization path.
* EVM token identity is already represented by
  `resolver-sim.shortfall.evm-asset/build-asset`, binding chain ID, contract
  address and unit into an asset root. It says nothing about issuer identity.
* No reusable stablecoin privacy policy schema, issuer signing role, scoped
  policy binding, trusted live head-observation source, or live disclosure
  adapter was found. A configuration root/self-hash cannot supply these.

Therefore V0 can implement content-addressed policy contracts and deterministic
offline evaluation. The evaluator must label currentness unknown unless an
external trust anchor and freshness observation are explicitly supplied and
verified. Adding a real active binding requires a separate authorized
configuration design/approval; no new authority store should be fabricated.
