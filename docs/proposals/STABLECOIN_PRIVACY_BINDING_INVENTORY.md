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
* The pending candidate now defines `stablecoin-privacy-policy.v1`, a rooted
  request/evaluation receipt, and offline signed binding/head-observation
  fixture contracts in `resolver-sim.privacy.policy` and
  `resolver-sim.privacy.resolution`. The candidate signing roles
  `:privacy-policy-authority` and `:privacy-head-observer` are verifier
  expectations only; no production keys or authoritative registry are
  established.
* No authorized privacy binding in PRF configuration, trusted production
  head-observation source, authenticated trust-policy distribution, or live
  disclosure adapter was found. A configuration root/self-hash cannot supply
  these.

Therefore this candidate's `:current-observed` resolution is qualified only
relative to caller-supplied trust policies and a signed fixture observation;
the normal privacy evaluator continues to report currentness unknown. Adding a
real active binding requires a separate authorized configuration
design/approval and authenticated production trust distribution; no new
authority store should be fabricated.
