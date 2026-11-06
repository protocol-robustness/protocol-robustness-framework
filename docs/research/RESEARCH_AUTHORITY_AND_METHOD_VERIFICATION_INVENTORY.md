# Research authority and method-verification inventory

This inventory records the existing PRF machinery used for the bounded
research-policy approval and conclusion-verification paths.

## Authority, signing, and group membership

- `research/ratification` builds content-addressed research policies and
  reconstructs researcher contributions against their eligible principals,
  threshold, dissent, and challenge rules. It does not establish that the
  policy's `:policy/authority-root` is trusted or approved.
- `researcher-force-authorisation` defines and verifies
  `researcher-decision.v2`. Its signed preimage binds the researcher,
  authorisation, request, review round, outcome, and signing-key ID.
- `review-governance` owns root-addressed principal/member/key snapshots,
  roles, independence groups and independence-basis references. Its
  constitution evaluator checks the governed panel before positions are read.
- `review-round` freezes a three-member panel and its purpose and policy
  binding.
- `decision-submission` and `governed_authority_state` retain and verify
  decisions against authenticated authority material. They are the authoritative
  path for governed operational decisions; research policy approval reuses
  their existing identity, membership, key and signed-decision contracts.
- `research-claim-acceptance` is an authority-issued claim assertion, but its
  decision evidence is an opaque root and is not itself a signed policy
  approval.
- `research-ratification-artifact` reconstructs ratification and checks that
  the authority root appears among supplied trust roots. It does not verify a
  signed approval of the policy.

The new research policy approval wrapper therefore uses the existing governed
review constitution and signed V2 decisions, then passes the verified approval
contributions through the existing ratification evaluator. It adds no signature
format or threshold algorithm. Researchers are members through the active
`:governance/members` entries, which bind member IDs to active principals and
their registered signing keys in one immutable governance root. The trust root
must be supplied by the caller and matched to that snapshot.

The bounded API is exposed through `resolver-sim.research.api` as
`build-research-policy-approval` and `verify-research-policy-approval`. A
threshold-satisfying receipt says only that the exact research policy was
approved under the supplied group policy. The receipt explicitly sets
`:approval/operational-authority?` to false. The committed Ed25519 keys under
`test/fixtures/signing-keys/research-policy-approvers/` are test-only keys and
must never be treated as real researcher identities or authority.

## Conclusion and method verification

- `research_conclusion/validate-conclusion` checks V1 structure, canonical
  hash, bounded scope, and falsifier references.
- `verify-conclusion-support` resolves each supporting theorem by hash and
  validates the theorem's canonical structure and hash.
- `qualify-conclusion` keeps structure, theorem support, and method status
  separate. Before this change, it accepted a verifier callback result bound to
  the conclusion and evidence roots, but did not require the callback's verifier
  identity to match an independently trusted root.

The new verifier-root requirement closes that trust-binding gap. A deterministic
integer-addition verifier is included as a narrow executable fixture, not as a
general scientific verifier. It recomputes the declared result from committed
integer inputs and returns rooted evidence. Its verifier root must still be
explicitly trusted by the caller. `qualify-research-conclusion` exposes this
read-only qualification through `resolver-sim.research.api`; it does not change
the V1 conclusion or authorize policy approval.

## Limits

Neither test fixture establishes a real organization's governance authority,
researcher independence, or a scientific method beyond the exact arithmetic
fixture. The approval verifier checks the caller-pinned governance root and
reconstructs the receipt, but it is not an external trust-root discovery or
policy-authorization service. Production callers must resolve the authority
snapshot, policy approval requirements, and verifier root from their own
trusted state. The arithmetic fixture only qualifies the exact committed
signed-64-bit addition inputs; it is not evidence for arbitrary claims or
theorem support.

## Verification

The focused deterministic fixtures and negative cases live in
`test/resolver_sim/research/policy_approval_test.clj` and
`test/resolver_sim/benchmark/research_conclusion_test.clj`. They are included in
the standard unit-test namespace inventory.
