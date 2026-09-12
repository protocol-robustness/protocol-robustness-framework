# Authority Continuity: PRF C3 and TUF Root Transitions

## Scope and conclusion

This note compares PRF's C3 configuration activation path with The Update Framework (TUF) root-role transition rule. The key distinction is that C3 authorizes and activates one exact configuration transition from one exact authoritative predecessor; TUF accepts a new root only when both the trusted old root and the candidate new root authorize it.

**Recommendation:** PRF does not need unconditional successor acceptance or dual authorization for every configuration change. It should require a TUF-style dual-authorization rule only for changes that alter the authority needed to validate or recover future governance: governance membership, signer-key eligibility, threshold/quorum semantics, authority resolver semantics, or recovery authority. Ordinary policy/content changes should remain predecessor-authorized and single-head CAS activated.

This recommendation preserves the existing C3 safety boundary while preventing a single compromised predecessor authority from installing an unilaterally controlled successor authority.

## Existing PRF model

C3a produces `configuration-transition-authorization-evidence.v1` from frozen predecessor material. It binds:

- the predecessor authoritative state, configuration, configuration head, and governance root;
- one exact `chain-configuration-transition` root;
- the resolved review-authority context, evaluation basis, and authorized authority report.

C3b recomputes that evidence and atomically publishes a derived successor only if the predecessor is still the current authoritative head. The publication is single-owner and predecessor-current; once the head advances, exact and conflicting replay reject. The successor configuration head is derived rather than selected by the caller.

The normal governed-authority path has the same shape: authority is evaluated against retained frozen material, a one-time fence is issued only for an authorized report, and finalization checks that the fence predecessor is still the current head.

## TUF root rule

For a root metadata update from trusted root `R0` to candidate root `R1`, TUF requires threshold-valid signatures under both roles:

- keys and threshold trusted in `R0`; and
- keys and threshold declared in `R1`.

TUF also requires monotonically increasing root version and expiry checks. Trust moves only when the old trust root delegates to the new root and the new root demonstrates possession of its own authority.

This is successor acceptance, not a requirement that old and new signers agree on all non-root metadata. Its purpose is continuity of the root of trust during key, role, and threshold transition.

## Invariants

| Invariant | C3 today | TUF analogue | Recommended PRF rule |
|---|---|---|---|
| Exact predecessor | C3a commits `E0/H0`; C3b requires E0 current | trusted root is the verification baseline | Retain |
| Exact successor | transition commits proposed configuration root; H1 is derived | candidate root is signed and versioned | Retain; make successor authority projection explicit for authority mutations |
| No forked activation | single-head CAS publication | client advances trusted root through accepted chain | Retain |
| Replay safety | old predecessor no longer current | root version prevents rollback | Retain; configuration epoch/version must remain monotonic |
| Old-authority authorization | required | required | Required for every configuration activation |
| New-authority possession | not generally required | required for root updates | Require only for authority-continuity mutations |
| Threshold validity | predecessor authority report establishes it | old and new threshold independently verify | Verify predecessor threshold always; verify successor threshold for authority-continuity mutations |
| Recovery continuity | implicit in successor configuration | root key rotation retains a verifiable next root | Require successor recovery path to be valid before replacing recovery authority |

## Threat model

| Threat | C3 protection | Residual risk | TUF-style mitigation |
|---|---|---|---|
| Stale authorization replay | predecessor-current CAS rejects it | none if publication owner is correct | no change |
| Caller substitutes successor | derived head and C3 evidence bind transition | availability body must still hash to committed root | no change |
| Compromised old quorum installs attacker-only governance | exact transition is authorized, so C3 accepts it | successor can exclude all independent recovery or review | require successor acceptance for authority mutation |
| Mistyped/invalid new keys | successor configuration can be structurally valid but operationally unusable | authority may become unavailable after activation | new authority signs acceptance; validate key/threshold usability |
| Threshold reduction captures future changes | old governance may legitimately authorize a lower successor threshold | post-activation capture is durable | classify as authority mutation and require successor acceptance plus policy constraints |
| Removal of last independent recovery key | predecessor may authorize self-lockout | no continuity proof remains | require valid successor recovery authority and acceptance |
| Mixed old/new signing during rotation | C3 has one predecessor decision | operational migration may be ambiguous | use a transition artifact with explicit old and successor attestations |

## Transition artifact

Do not overload generic configuration-transition authorization evidence. Add a distinct, closed, rooted artifact only if the dual-authorization rule is adopted:

`authority-continuity-transition.v1`

Required semantic fields:

| Field | Meaning |
|---|---|
| `:predecessor-authoritative-state/root` | Exact E0 at which predecessor authority was evaluated |
| `:configuration-transition/root` | Exact C3 transition, including parent and new configuration roots |
| `:predecessor-authority-report/root` | Authorized report under E0 authority |
| `:successor-authority-projection/root` | Canonical projection of successor governance, keys, thresholds, resolver semantics, and recovery authority |
| `:successor-acceptance-report/root` | Report/attestations verified under that successor projection |
| `:continuity-class` | Declares why successor acceptance is required |
| `:recovery-continuity/root` | Evidence that an allowed recovery path exists after activation |

The artifact must be verified against a mechanically derived successor configuration and must not permit a caller-selected successor. C3b remains the only publication owner and consumes the artifact in the same predecessor-current CAS as activation.

## Rotation

For a key or member rotation that leaves effective authority semantics unchanged, predecessor authorization plus structural validation is sufficient when the successor retains a verified threshold of reachable keys.

For a rotation that changes the authority set, validate both sides:

1. Old authority authorizes the exact configuration transition at E0.
2. Successor authority signs acceptance of the exact transition or successor authority projection.
3. C3b activates only while E0 is current.

The successor acceptance should bind the transition root, chain instance, predecessor root, and successor authority projection root. It must not be a free-standing statement such as “I accept governance.”

## Compromise

Normal rotation assumes predecessor authority remains trustworthy enough to authorize its successor. Compromise recovery is different: requiring predecessor authorization can make recovery impossible, while accepting any claimed recovery authority enables takeover.

Use a separately configured recovery authority with an explicit scope and activation path. Recovery should:

- be precommitted in the active configuration;
- be disjoint from, or at least independently thresholded from, normal governance where the threat model requires it;
- authorize only narrowly scoped authority-recovery transitions;
- require a higher threshold, delay, external evidence, or both;
- produce an auditable recovery artifact that names the compromised authority projection and the exact successor.

Successor acceptance is useful but cannot be the sole recovery condition: an attacker who controls the proposed successor can also sign as it. The security condition is authorization by a recovery authority trusted before compromise.

## Removal

Removing a non-critical member/key is an authority mutation when it changes the current authority projection. Require predecessor authorization and successor acceptance if removal changes membership, signer eligibility, quorum reachability, recovery authority, or the ability to validate a future transition.

Do not permit removal when it leaves no valid successor threshold or no valid recovery path. A removal that is semantically inert to the authority projection can remain an ordinary configuration change.

## Threshold change

Treat every threshold or quorum change as an authority-continuity mutation, including changes that appear safer. A higher threshold can create liveness loss; a lower threshold can create capture. Require:

- predecessor authorization under the old threshold;
- successor acceptance under the new threshold;
- satisfiability checks for both old and new signer sets;
- an explicit policy decision on overlap.

Overlap between old and new signer sets is valuable operationally but should not be a universal validity requirement. It is impossible for some legitimate emergency replacements and is weaker than independent old/new threshold verification. Make overlap a risk-policy control, not the cryptographic continuity primitive.

## Recovery

There are two cases:

| Case | Required authorization |
|---|---|
| Planned recovery or rotation while old authority is available | predecessor authorization, successor acceptance for authority mutation |
| Compromise recovery where old authority is unavailable or unsafe | precommitted recovery authority, narrow recovery transition, successor acceptance where meaningful |

Recovery must not silently bypass the configuration-head lineage. It creates a successor of the current authoritative state through the same single publication owner, with a distinct transition type and evidence profile.

## Mutation matrix

| Mutation | C3 predecessor authorization | Successor acceptance | Recovery authority | Notes |
|---|---:|---:|---:|---|
| Non-authority policy/content | Yes | No | No | Existing C3 is sufficient |
| Add spare signing key without changing threshold or eligibility | Yes | Usually no | No | Require successor acceptance if key becomes independently eligible authority |
| Replace key for existing member | Yes | Yes | No | Prevents dead-on-arrival successor key set |
| Add/remove governance member | Yes | Yes | No | Authority projection changes |
| Change signer eligibility | Yes | Yes | No | Authority projection changes |
| Raise/lower threshold or quorum | Yes | Yes | No | Safety and liveness both change |
| Change resolver semantics/policy | Yes | Yes | No | It changes how future authority is decided |
| Change recovery authority | Yes | Yes | Existing recovery path remains applicable until activation | Must not remove all recovery paths |
| Emergency compromise recovery | No, if explicitly unavailable/unsafe | Conditional | Yes | Recovery authority must be committed before the incident |
| Revoke compromised key with old quorum still available | Yes | Yes | Optional | Treat as authority mutation |

## Decision

PRF should retain C3's predecessor-bound, derived-successor, single-head activation model. Add successor acceptance narrowly for authority-continuity mutations. This is the smallest design that obtains TUF's key continuity benefit without turning every configuration activation into two independent governance votes.

Relevant implementation references: `src/resolver_sim/benchmark/configuration_transition_authorization.clj`, `src/resolver_sim/benchmark/configuration_activation_publication.clj`, `src/resolver_sim/benchmark/governed_authority_state.clj`, and `src/resolver_sim/benchmark/governed_authority_derived_finalization.clj`.
