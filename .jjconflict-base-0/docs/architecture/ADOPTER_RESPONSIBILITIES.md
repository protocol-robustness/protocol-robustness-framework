# Adopter Responsibilities

PRF provides composable assurance mechanisms. It does not automatically define
the protocol, economic model, authority policy, or operational meaning of a
passing result. A protocol author or adopter must instantiate those semantics
and state the scope of every claim.

| Area | PRF provides | Adopter must provide |
| --- | --- | --- |
| State and transitions | Replay, snapshots, invariants, canonical artifacts | State meaning, legal transitions, terminal states, and protocol invariants |
| Successors | Predecessor binding, lineage, fencing, and CAS patterns | Linear/branching rules, correction and merge semantics, and successor eligibility |
| Admission | Verification and fail-closed decision boundaries | Policy, authority source, evidence requirements, commit effects, and rejection handling |
| Economics | Reconciliation and closed-form validation machinery | Liabilities, claimability, fees, bonds, payouts, solvency meaning, equations, and assumptions |
| Game theory | Replay forks, bounded evaluators, and evidence output | Players, utilities, information sets, strategies, continuation model, calibration, and coverage |
| Authority | Rooted bindings, signatures, capability resolution, and policy checks | Trust roots, roles, quorum, governance, revocation, and key custody |
| Finalization | Fences, exact-state checks, and single-writer patterns | Authoritative store, transaction boundary, recovery, retention, and availability model |
| Assurance claims | Versioned evidence, verification, and scoped result formats | Claim scope, exclusions, threat model, evidence threshold, and publication decision |

Sew, pro-rata, dispute-resolution, risk, and notebook workflows are reference
implementations or worked examples. Their semantics are not silently promoted to
universal PRF rules.

Before publishing a claim, identify the adopter-owned definitions, the exact
validation workload, omitted cases, and residual risks. A passing example proves
only its declared model and evidence scope.
