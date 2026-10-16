# Authority state v1

`resolver-sim.authority-state` defines additive canonical contracts. Existing
`eae-controlled-entitlement-demo.authority-state.v1` roots are unchanged.
These schemas do not assert approval, deploy an authority, or enforce adapter
storage mutations. Adoption by a deployment requires explicit policy approval
and a validated update path.

## Generic state

`authority-state.v1` is a closed map with these required fields:

| Field | Meaning and constraint |
| --- | --- |
| `:authority-state/schema` | Exactly `"authority-state.v1"` |
| `:protocol/genesis-root` | Canonical lowercase SHA-256 reference |
| `:execution/chain-id` | Integer in `[1, 2^256)` |
| `:authority/policy-root` | Canonical SHA-256 root of the applicable authority policy |
| `:authority/revision` | Integer in `[0, 2^64)` |
| `:authority/previous-root` | `nil` at revision zero; predecessor SHA-256 root thereafter |
| `:authority/decisions` | Vector sorted uniquely by canonical decision root |

Each decision is a closed record with `:decision/root`, `:decision/revoked?`
(boolean), and `:decision/proof-mode` (`nil`, 0 or 1). The initial decision
vector is empty. A decision root identifies the canonical configuration
transition being authorised. Its preimage must be independently validated
using the existing configuration-transition contract before authorisation;
state validation alone checks the reference and lifecycle, not that preimage.
`nil` means no proof mode was assigned; it is distinct from explicit mode 0.

The canonical root is `sha256:` plus lowercase hex from the repository's
`domain-hash` implementation using `PRF_AUTHORITY_STATE_V1` and
`project-authority-state-v1`. This uses PRF canonical encoding, not JSON or
Solidity ABI encoding. Every field participates in identity. Unknown keys,
unknown nested decision fields, duplicates and unsorted inputs fail validation;
validation never silently sorts or drops fields.

## Local authority policy

`local-authority-policy.v1` is a separate closed map containing
`:authority-policy/schema` (exact schema string), `:protocol/genesis-root`,
`:execution/chain-id`, `:authority/governor` (nonzero lowercase EVM address),
and `:authority/capabilities` (nonempty, sorted, unique string vector).
Supported capabilities are `authorise-decision`, `revoke-decision`,
`set-proof-mode`, and `update-authority-state`. Its domain is
`PRF_LOCAL_AUTHORITY_POLICY_V1` and its projection is
`project-local-authority-policy-v1`.

For transition execution, additive `local-authority-policy.v2` retains all v1
fields and adds a validator key ID plus its raw 32-byte Ed25519 public key. Its
separate `PRF_LOCAL_AUTHORITY_POLICY_V2` domain commits that validator identity.
The v2 policy is required by `authority-state-transition.v1`; v1 remains valid
for historical derivations but cannot authorize the new attested path. The
known vector in `data/fixtures/authority/local-authority-policy.v2.edn` is a
schema/test vector, not an approved deployment key.

`validate-policy-binding` checks root equality and matching protocol/chain
scope. The generic state can commit other policy schemas in future, but the
current policy-binding and transition validator accepts this local policy
only. No SEW voting or timelock model is embedded in the generic projection.
An execution chain ID is not a unique deployment-instance identifier. Instance
binding remains the separate chain-instance genesis and transition target.

## Transitions and enforcement

`validate-transition` accepts the previous state, successor state, current
policy and successor policy. The revision advances by one, the predecessor
root matches exactly and protocol/chain scope stays fixed. Decisions cannot
be removed or restored after revocation. Assigned proof modes cannot be
replaced or removed, and revoked decisions cannot acquire proof modes.
New decisions must start authorised. Required capabilities are evaluated
against the current policy, including when replacing that policy; the
successor cannot grant itself permission to make the transition.

The `authority-state-transition.v1` envelope binds previous/successor state
roots, policy roots, revisions, instance genesis root, adapter address, derived
mutation payload and exact configuration-transition preimages for newly added
decision roots. Preimages are rehashed through
`chain-configuration-transition.v1` and must target the bound instance. The
envelope builder rejects policy replacement. Its attestation verifier checks
the Ed25519 signature against the key committed by local-authority-policy.v2
and an independently provisioned trust policy.

Every transition requires `update-authority-state`. New decisions additionally
require `authorise-decision`, revocations require `revoke-decision`, and proof
mode assignments require `set-proof-mode`. These checks describe permitted
changes; they do not authenticate the caller, the policy's approval or live
EVM storage. The existing adapter still permits separate governor-controlled
mutations. The attested transition is currently an off-chain semantic and
signature gate; a deployment must bind it to one atomic EVM execution path and
remove the direct administrative bypasses before claiming authority-state
continuity.

The deployment CLI exposes `generic-authority-state` and
`local-authority-policy` (v1 and v2). They report `derived-only-not-approved`. The older
`authority-state` command continues to derive the demo candidate unchanged.

Known-good state and policy inputs and expected roots are pinned in
`data/fixtures/authority/canonical-authority-state.v1.edn` and
`data/fixtures/authority/local-authority-policy.v2.edn`. Transition and
malformed-input tests are in `test/resolver_sim/authority_state_test.clj`.
