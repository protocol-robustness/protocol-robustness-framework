# PRF Resolution Lifecycle and Closure V1

Status: **Proposed implementation contract — not implemented or independently accepted**  
Date: 2026-11-27  
Owner: PRF research/ratification; domain adapters own their semantics  
Target project: [PRF Research Ratification & Sew Dispute Reference](https://linear.app/protocol-robustness-framework/project/prf-research-ratification-and-sew-dispute-reference-0a4afbd7f0f1)  
Priority: P0 challenge completeness, disclosed dispositions and independently verifiable historical closure; P1 remediation verification, current applicability and presentation.

## 1. Purpose, claim and limits

A PRF research resolution must answer, with reconstructable evidence:

1. **What exact proposition was evaluated?** Identify domain, subject, revision, scope, evidence/method, review round, policy and frozen authority.
2. **What is the complete in-scope challenge set at a declared cutoff?** Bind a replayed, authenticated history prefix and enumerate included, excluded, duplicate and inadmissible entries without silently omitting an objection.
3. **What happened to each challenge?** Record a reasoned, attributable disposition, supporting evidence and any remediation or authorized risk acceptance.
4. **Who reviewed or ratified the result, and under which policy?** Independently validate signatures, principal eligibility, threshold, dissent and exact-target binding; do not substitute a majority label for a complete review.
5. **Was the case historically closed under its policy?** Derive a closure artifact and verification receipt, rather than accepting a claimed closed flag.
6. **Is that closure applicable to a requested subject and time now?** Make this a *separate* successor observation/assessment, never rewrite the historical closure.

Resolution is **research/assurance disposition**, not automatic truth, on-chain arbitration, Sew adjudication, settlement authority, payment authorization, safety certification, regulatory compliance or global network consensus. An unsuccessful or incomplete resolution is a valid research result but **must not be displayed as closed**. Scientific correctness and external-world completeness are established only by the explicitly verified methods and trust assumptions, not by hashing.

The V1 implementation must be framework-wide. Sew is a reference adapter, not the owner of the generic lifecycle.

## 2. Existing primitives to reuse (do not fork)

| Existing surface | Reuse | Missing semantics supplied here |
| --- | --- | --- |
| \`src/resolver_sim/research/ratification.clj\` | \`research-target.v1\`, \`research-ratification-policy.v1\`, signed \`research-contribution.v1\`, \`reconstruct-ratification\`, \`validate-ratification\` | Complete challenge inventory, dispositions and closure gates |
| \`src/resolver_sim/research/resolution_view.clj\` and \`schemas/prf-research-resolution-view.v1.json\` | Read-only, method-/reproduction-/ratification-aware presentation; always \`operational-authority? = false\` | Separately reported completeness, closure and applicability — **add v2 projection**, do not mutate v1 |
| \`src/resolver_sim/network/researcher_challenges.clj\` | Signed-file actions, controlled replay, review dossiers, history checkpoint verification | Domain-adapted in-scope challenge enumeration; current V0 network challenge tasks and rewards are not automatically research-resolution objections |
| \`docs/specs/NETWORK_CHECKPOINT_PROTOCOL_V1.md\`; PRO-92/93/94 | Local history reconstruction, certification and optional independently authenticated head observation | Research-case completeness under an expressly declared intake/source universe; certification is not global completeness or latestness |
| Research claim acceptance, forensic evidence and content-root helpers | Existing hashes, verification, result/evidence references, trusted signer bindings | Disposition-specific evidence obligations and explicit closure verification |
| PRO-57/58/59, PRO-60/61/62/63 | Prior ratification and Sew reference work | No replacement signatures, panel, workflow DSL, replay engine or duplicate dispute registry |

Critical current boundary: \`ratification.clj\` can produce \`:ratified\` while its contribution list is merely the supplied list. Its \`complete?\` variable refers to coverage of eligible principals in that list, **not to a complete challenge intake history**. A \`ratification/challenge-roots\` vector by itself is neither a disposition register nor proof that all challenges were supplied.

Existing \`SEW_DISPUTE_RESOLUTION_CLOSURE_PHASE_5A.md\` concerns a **different** post-settlement reconstruction. Do not insert research-closure roots into Sew semantic settlement/effect/state roots, or change existing V1 terminal envelopes in place. A later Sew adapter may reference the research closure as upstream evidence only.

## 3. Vocabulary and lifecycle

- **Case:** exact target plus scope, policy, authoritative history, review-round and predecessor/successor lineage. A navigational alias is never authority.
- **Challenge:** an authenticated or observed in-scope objection, counterexample or disputed claim with a unique root and declared provenance. A rejected challenge remains in the intake inventory.
- **Disposition:** one authoritative decision about a challenge and why, with reason/evidence, rule, reviewer/authority and stable identity; it does not make its scientific assertions true.
- **Completeness:** every entry in the *declared and verified intake universe up to a cutoff* is accounted for. Not a claim that no undisclosed objection exists anywhere.
- **Remediation:** a change or bounded narrowing of claim and the verified evidence of its effect, distinct from a promised action.
- **Ratification:** existing policy-conformant researcher assessment; never by itself a disposition or closure.
- **Historical closure:** an immutable derivation from verified inputs under a specific versioned policy and cutoff.
- **Applicability:** a separately verified, as-of assessment of whether a historical closure still applies to the requested current subject, method, revision, dependency and authority context.

Ordered dependencies:

~~~text
exact claim / scope / policy / trusted history
  -> complete challenge intake and cutoff receipt
  -> per-challenge disposition + optional verified remediation / accepted exception
  -> existing independently reconstructed researcher ratification and dissent
  -> historical research-resolution closure
  -> independent verification receipt
  -> later applicability assessment and current-head observations
~~~

A valid case may be \`open\`, \`under-review\`, \`blocked\`, \`historically-closed\` or \`historically-closed-with-accepted-exceptions\`. This is a derived presentation state, **not** a mutable canonical status field with unilateral authority. New evidence after the cutoff starts a successor case/review, not an edit of the old root.

## 4. Canonical inputs and authority model

Every case binds the existing exact \`:target/root\`, \`:target/subject-root\`, \`:target/evidence-root\`, \`:target/round-root\`, \`:target/policy-root\` and optional \`:target/predecessor-root\`. It additionally binds a versioned resolution-policy root containing:

- Supported case/claim domain and disposition policy; explicit non-vacuous closure criteria.
- Challenge admission/intake source IDs and authoritative genesis/checkpoint; deterministic scope predicate; admission/rejection rules and cutoff ordering/time semantics.
- Permitted disposition categories, per-category evidence requirements, deadline/timeout policy where relevant, and explicit exceptional-closure rules.
- Separate authority snapshots for intake/history, challenge disposition, remediation verification, exception acceptance, ratification and optional publication. Require authenticated principal/key bindings, membership version, independence requirements and action-specific permissions. One actor holding multiple roles does not magically satisfy independent-review requirements.
- Method/evidence and independent-reproduction requirements, where claimed; verifier identity/version and method source pinning.
- Requested as-of freshness policy for **applicability only**, including source independence when multiple head observations are required.

Re-use existing canonical hashing and signed-decision/authority verification. Every new artifact has a **new, closed, domain-separated schema**. Compute the root from the entire canonical payload **excluding its own root field**; refuse unknown fields, duplicate IDs, mismatched root encodings, malformed keys and noncanonical ordering. Include the root algorithm/domain/version in the specification and golden vectors; do not accept arbitrary caller-provided roots or raw file paths as proof.

The verifier receives trusted genesis/history anchors, policy/authority roots, key bindings and allowed methods from its **own** pinned inputs. They must not come exclusively from the artifact under review. Signature authentication establishes key control only, not legal identity or independence.

## 5. Required canonical V1 artifacts

These names are proposed **new** contracts; none is claimed to exist in the checked-in code yet. Fields below are the required semantic contract. Implementers may normalize the exact names before schema freeze, but must not weaken the invariants.

### 5.1 \`prf/research-resolution-policy.v1\`

Rooted versioned wrapper/reference for the existing \`research-ratification-policy.v1\` plus intake universe, disposition gates, method profile and authority snapshots. Bind the existing policy *by root*, do not copy/reimplement its quorum logic. An invalid/unknown ratification-policy version blocks closure.

### 5.2 \`prf/research-challenge-completeness.v1\`

Required: case/target/round/policy roots; source inventory with required vs optional designation; trusted history genesis/checkpoint roots; exact cutoff prefix (sequence/head root, and time if the history provides authenticated time); source-specific completeness statements; deterministic scope-predicate version; canonical sorted challenge roots and their admission classifications; rejected/excluded entry roots with reasons; unresolved, missing, conflicting, unavailable or quarantined entry references; manifest root and \`completeness-root\`.

Verifier algorithm:

1. Verify each required intake source from independently pinned anchors and reconstruct the complete allowed prefix up to its frozen cutoff. Check predecessor chain, signatures and version; reject gaps, equivocation, malformed entries, foreign cases, unaccounted branches, missing required sources and untrusted heads.
2. Apply exactly the versioned scope predicate to *every* source entry, including rejected or invalid entries. Deduplicate by identity and retain aliases; a duplicate must point to one canonical entry and cannot hide another independent objection.
3. Recompute exact sorted included, excluded and quarantined roots/counts, compare to committed manifest and reconstruct \`completeness-root\`.
4. Report \`:complete-within-declared-universe\` only if all required input prefixes and exclusions are independently verifiable. A declared empty set passes only with a verified complete empty prefix. Missing information yields \`:not-established\` or \`:conflicting\`, **never an empty verified set**.

A certified network head/append-only log is evidence for a particular history/prefix under its trust policy, not evidence that a remote omitted challenge never existed. Document the declared universe and its observer/availability assumptions in every result.

### 5.3 \`prf/research-challenge-disposition.v1\`

One immutable record per canonical challenge. Must bind case/target/round, challenge root, completeness-root/cutoff, disposition policy, decision authority snapshot, reviewer signature/verification, \`reason-code\`, human-readable explanation reference, evidence/method verification roots, timestamp basis (if authenticated), predecessor/supersession link, and the disposition root.

Allowed \`disposition/status\` values:

| Status | Required meaning / gate |
| --- | --- |
| \`:upheld\` | The objection is supported under a qualified method; claim must be narrowed, corrected, withdrawn or explicitly risk-accepted before closure |
| \`:partially-upheld\` | Record the accepted subset and residual unresolved scope; verify remediation or authorized exception for the residual |
| \`:dismissed\` | Provide a reason, qualified rebuttal or evidence assessment; reviewer authority must be valid; not a silent rejection |
| \`:inconclusive\` | Evidence insufficient; blocks ordinary closure unless a named policy/authority explicitly accepts a bounded exception |
| \`:withdrawn\` | Authenticated withdrawal by the entitled claimant or an explicitly authorized process; the challenge stays visible |
| \`:superseded\` | Valid successor challenge/disposition root and lineage; unresolved successor still blocks ordinary closure |
| \`:duplicate\` | Point to a prior canonical challenge root and verify same underlying challenge identity; must not erase distinct evidence |

Dispositions are not freely overwritable. One canonical effective disposition per challenge/cutoff, with explicitly linked successor revisions. Disposition authors must not self-certify a method or a remediation unless the applicable policy explicitly permits that non-independent process; limitations remain visible. An ineligible or invalid signature is an invalid disposition.

An **accepted exception/risk** is a *separate* explicitly scoped record approved under the frozen exception-authority policy; it does not change \`:upheld\` to \`:dismissed\` or conceal \`:inconclusive\`. Include impact, affected claim slice, expiry/review condition and liability/operational implications as declared metadata, without creating financial obligations.

### 5.4 \`prf/research-remediation-verification.v1\` (conditional)

Required whenever closure claims a verified correction: target/challenge/disposition roots; claimed correction or narrower proposition; before and after revision roots; reproducible method/fixture inputs, expected vs actual checks; evidence roots; independent verifier/authority; status \`:verified\`, \`:failed\` or \`:not-established\`; immutable root. A planned patch, merged commit or passing unrelated test is *not* verified remediation. Do not imply an implementation change if the case is closed via bounded withdrawal or accepted risk instead.

### 5.5 \`prf/research-resolution-closure.v1\`

Derived, immutable artifact, not an operator declaration. Required bindings:

- \`case/target-root\`, \`case/scope-root\`, \`case/review-round-root\`, predecessor-root, resolution-policy root and trust-anchor identifiers.
- Exact \`completeness-root\`, cutoff/prefix roots and **complete sorted challenge set**, with full disposition-root mapping.
- All required remediation verification roots, accepted-exception roots, method/claim-evidence verification roots and limitations.
- Existing \`ratification/root\` plus independently reconstructed result/eligible member set and preserved dissent/challenge references.
- Deterministically derived status: \`:closed\`, \`:closed-with-accepted-exceptions\` or \`:blocked\`, with machine-readable blockers and closure root; a blocked result is reportable but never a closure certificate.
- \`operational-authority? = false\` and explicit scope of the conclusion.

**Closure predicate:** only produce a success status if (a) target/policy/authority and all mandatory histories verify; (b) completeness is established under the declared universe; (c) each canonical challenge has exactly one effective authorized disposition; (d) all upheld/partially upheld/inconclusive material objections are corrected with verified evidence, removed by a correctly rooted scope narrowing, or have a policy-permitted, independently authorized bounded exception; (e) required eligible decisions/ratification and allowed dissent policy are independently reconstructed; and (f) no unresolved conflict, missing required verification or incompatible successor is suppressed.

Do **not** infer closure from \`:ratified\` alone, a signed "resolved" label, finality of a separate network checkpoint, or settlement completion. If a mandatory premise is unavailable, status is \`:blocked\`/\`:not-established\`, not \`:closed-with-accepted-exceptions\` by default.

### 5.6 \`prf/research-resolution-applicability.v1\`

A separate, versioned, as-of assessment of a *verified historical closure*. Bind closure root; requested subject and revision; method and dependency roots; current policy/membership/head evidence; as-of observation and freshness basis; valid-from/expiry terms; supersession/invalidation references; trust-source/observation roots; status and limitations.

Allowed statuses: \`:current-observed\`, \`:stale-or-unknown\`, \`:superseded\`, \`:expired\`, \`:invalidated\`, \`:conflicting\`. \`:current-observed\` requires verified compatible current context and *policy-required* fresh authenticated source observations; it does not prove global latestness. If current observations are unavailable, retain historical closure and report \`:stale-or-unknown\`. New contradictory evidence or policy change cannot retroactively change the historical closed-at-cutoff claim; it can invalidate **current applicability** and trigger successor review.

Publication and later observations may reference closure/applicability roots; **closure must not contain a future publication, head, or applicability root**, preventing cyclic identities.

### 5.7 \`prf/research-resolution-verification.v1\`

Verifier-owned, reproducible receipt with verified artifact roots, method implementation/version/source identity, externally pinned trust anchors, verification cutoff, independently derived results for: structural integrity, authority, history completeness, disposition coverage, remediation, ratification, historical closure and current applicability; typed errors, limitations and verifier receipt root.

Statuses are independent, not collapsed into one \`verified\` boolean. Unknown/unavailable input is distinct from cryptographic contradiction. A receipt is evidence of the verifier's checks **only**; readers must check the verifier identity and source pins before trusting it. Do not let a caller-provided verification receipt substitute for re-execution.

## 6. Deterministic verification algorithm

Input: complete retained case bundle, signed histories, exact policy snapshots, pinned trust roots and source descriptors, optional as-of observations; **no network access needed for historical verification**.

1. Validate closed schema, canonicalization, artifact hash and referential DAG for all supplied objects. Reject cycles and root substitution.
2. Reconstruct exact-target identity, evidence/method provenance and applicable signed policy/key memberships; reject foreign case, replay and role confusion.
3. Verify each declared required intake history and checkpoint/prefix, recompute complete challenge manifest including excluded and inadmissible records; refuse unproven "no challenges".
4. For each canonical challenge, verify the effective disposition signature/authority, reason, evidence reference, successor chain and one-to-one coverage; detect contradictory dispositions and omission.
5. Independently verify each claimed remediation or accepted exception, including cross-revision scope and expiration, according to policy.
6. Reconstruct existing \`research-ratification.v1\` from signed contributions and policy; preserve dissent and compare exact target/round/evidence lineage. Verify any required method evidence and independence, not just threshold counts.
7. Recompute closure predicate, blockers and root. Compare exact persisted closure; fail closed on status escalation or missing mandatory input.
8. Optionally evaluate applicability using caller-independent, separately pinned trusted head observations and as-of policy. Report unknown and conflicting sources without choosing the latest-looking head.
9. Produce a deterministic structured verification report/receipt. Same canonical bundle and pins produce the same historical result across fresh processes; the as-of assessment is explicitly time/observation-dependent.

Suggested pure interface (names are proposed, not an API already implemented):

~~~clojure
(build-challenge-completeness frozen-policy intake-histories cutoff)
(verify-challenge-completeness trusted-inputs manifest)
(validate-disposition trusted-inputs completeness disposition)
(reconstruct-research-closure trusted-inputs bundle)
(verify-research-closure trusted-inputs closure bundle)
(assess-resolution-applicability trusted-currentness-inputs closure as-of)
~~~

Historical verification must not silently fetch current web heads, consult mutable defaults, or mutate research/settlement state. Surface all missing/unsupported method adapters as \`:not-established\`.

## 7. Negative tests and acceptance vectors

The following must each be runnable as deterministic fixtures with golden roots and exact expected errors:

| ID | Scenario | Expected |
| --- | --- | --- |
| RLC-01 | No challenges with an authenticated complete empty source prefix | Completeness can pass; ordinary closure still requires all other gates |
| RLC-02 | One omitted in-scope challenge; swapped prefix/manifest | Invalid or not-established completeness; no closure |
| RLC-03 | Missing source, truncated history, forked/equivocated head, malformed/signature-invalid intake | Reject or conflicting; never silently drop the input |
| RLC-04 | Duplicate evidence wrapper vs genuinely distinct challenge | Deduplicate only true canonical duplicates; preserve distinct objections |
| RLC-05 | Every disposition category, reason/evidence and actor entitlement | Appropriate validation; unsupported/inconclusive does not silently become dismissed |
| RLC-06 | Unresolved upheld/partial/inconclusive challenge or unverified remediation | Block ordinary closure |
| RLC-07 | Authorized bounded exception; then wrong role, expired exception, conflicting acceptance | Only the valid policy-allowed exception produces explicitly qualified closure |
| RLC-08 | Duplicate/conflicting disposition, wrong case/round, stale signer, replayed signature | Fail closed; unchanged prior accepted record |
| RLC-09 | Valid ratification with hidden challenge; valid challenge history with inadequate ratification | Both block success; no conflation |
| RLC-10 | Dissent/minority report vs quorum; unverified methods or same-implementation reproduction | Preserve separate dimensions and limitations; no overclaim |
| RLC-11 | Changed subject/revision/dependency or new contrary evidence after cutoff | Historical closure unchanged; current applicability stale/superseded/invalidated |
| RLC-12 | Two inconsistent authenticated heads, one stale head, missing freshness data | Conflicting or stale-or-unknown; never global current |
| RLC-13 | Tampered closure root, missing artifact, alternate source trust root, changed policy | Verification fails; no caller-provided trust substitution |
| RLC-14 | Sew reference case using pre-existing exact-frame and adjudication evidence | Research closure verified independently but has no settlement/appeal authority |

Additional mandatory properties: no cross-case contamination; no double disposition; no retroactive reinterpretation of superseded policy; no success on an empty required-verification list; preservation of explicit limitations; no difference between independent clean-process reproduction and same-process helper results hidden by presentation.

## 8. Integration and presentation

- Implement in the **existing PRF research module boundary**; avoid an extracted repository, separate database, network service or new signature subsystem. Keep proof/authority verification independent of the adapter that assembled the claimed closure.
- New strictly versioned schemas in \`schemas/\`; small canonical constructors and pure verifiers under \`src/resolver_sim/research/\`. Existing V1 ratification and \`prf-research-resolution-view.v1\` remain byte- and meaning-compatible. An explicit v2 read-only projection displays completeness, disposition totals, dissent, remediation and separate historical/current verdicts.
- Sew adaptation belongs in the Sew reference application (PRO-60/61/62). Use the frozen Sew case/basis, authenticated ruling and rooted replay frames. Do not reimplement Sew economics or infer on-chain finality. The separate post-settlement SEW closure proposal remains governed by its own acyclic ordering.
- Researcher challenges V0 (PRO-63/124) may supply evidence of submissions only after its exact intake history and authorized review actions satisfy this contract. A task-claim/reward history is **not** automatically the case's complete challenge universe.
- Report plain-language readback: "Closed as of cutoff X under policy Y for scope Z; N challenges accounted for, M upheld, K unresolved exceptions; historical verification V; current applicability A and evidence age T." Never a bare green "resolved."

## 9. Delivery sequence and non-goals

1. **Inventory/freeze:** exact existing consumers and source ownership; adopt challenge universe, closure policy, role matrix and explicit trust limits.
2. **Schemas/vectors:** canonical new contract shapes and golden cross-process hash fixtures, without altering existing V1 shapes.
3. **Completeness:** signed-history reconstruction and exact admitted/rejected/duplicate set.
4. **Disposition:** authenticated per-challenge outcomes, reasoned disclosures, successor/exception policy.
5. **Remediation:** qualified correction evidence and accepted-risk controls.
6. **Closure:** deterministic independent verifier and report.
7. **Applicability:** as-of policy and authenticated head comparison, separated from historical closure.
8. **Readback/conformance:** v2 research resolution view, full negative suite, one generic analytical case and a Sew reference case where existing PRO-61/62 prerequisites permit.

Do **not** introduce a broad arbitration protocol, incentive token, on-chain registry, mandatory blockchain anchoring, a new governance network, configurable workflow DSL, or operational capital permission. Do not mark resolution research accepted merely because a design document or a successful self-generated demo exists.

## 10. Definition of done and release gate

- Exact artifact schemas, independently replayable fixtures, hash domains, canonical policy and trusted key bindings are published and frozen.
- All RLC-01…14 vectors and deliberate tampering cases pass against an **independent verifier** in a clean environment; the producer's root/decision flags are never trusted.
- One generic PRF case demonstrates all stages, with a dissent and both an upheld and dismissed challenge; deliberately incomplete case remains visibly blocked.
- Read-only v2 readback distinguishes case claim, method, completeness, dispositions, ratification, historical closure and as-of applicability.
- One Sew case may be used only with its own authenticated case/ruling/effects and may not broaden this contract's authority.
- Publication of the exact candidate/revision/evidence root and independently reviewed acceptance is a separate gate. Retain dissent and outstanding issues in the published record.
- Older V1 schemas/consumers continue to validate unchanged; any new required input uses a distinct versioned envelope.

## 11. Cross-protocol research lessons (requirements, not imported protocols)

- Optimism/Arbitrum: bounded challenge scope and rooted counterclaims; an appeal/dispute result must be tied to its exact branch, deadline and finality conditions.
- Kleros: visible evidence, ruling lineage, appeals and participation; votes alone do not prove complete input evidence or truth.
- Snapshot: distinguish quorum, voting/policy and execution authority.
- in-toto: typed provenance and exact subject/material/product binding.
- Rekor: append-only auditability and inclusion/consistency proofs; a log cannot certify that every relevant outsider submitted an objection.
- TUF: threshold key authority, rotation, expiry and current trusted metadata; historical signatures are not a currentness certificate.

These comparisons motivate the contract. PRF retains its own trust and economic semantics; no external protocol is adopted as a required dependency.

## 12. Review record / open design decisions

Normative before implementation acceptance:
- Select the minimum authoritative intake sources per case, their declared coverage boundaries and cutoff/fence semantics; name a policy owner for those sources.
- Freeze which disposition outcomes require independent method verification and which explicitly scoped risks can be accepted; select a distinct exception authority where required.
- Set reviewer membership/key-rotation trust inputs, appeal/reconsideration timeouts if applicable, and method provenance requirements.
- Decide whether v1 supports multiple intake-source branches or rejects them fail-closed. No merge by arrival time.
- Freeze exact new schema field names, IDs, hash-domain labels and conformance fixture roots before producing code; unsupported fields remain rejected.

**Implementation and independent security/research review have not occurred for this contract.** This document defines acceptance requirements and intended work, not assertions that those capabilities already exist.
