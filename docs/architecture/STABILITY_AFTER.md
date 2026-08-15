# Stability-after (derived-not-declared authority)

## The rule

**Broad rule.** Authority attaches after **independent derivation or
verification**, never from the declarative shape of a supplied field. An
authoritative status, result, aggregate, root, or verdict is never
authoritative merely because supplied content declares it.

**Independence** is with respect to the authority-bearing assertion, not the
implementation language or process. A check or verification is not independent
if it ultimately trusts the same supplied result-shaped field it is supposed
to establish — e.g. a caller that reads `:pass` and a helper that returns
`:pass` are not two independent checks. Only a recomputation from a basis that
is distinct from, and not derived from, the asserted field confers independent
authority; a verifier that reads `:pass` and produces `:pass` establishes
nothing.

"Supplied content" includes user-supplied use-case bundles, example
interpretations, extension boundaries, evidence bundles handed to a renderer,
and any command that consumes those inputs.

"Stability-after" is the project shorthand for this rule. The more precise
technical invariant is **derived-not-declared authority**: the boundary is not
always a literal "recompute a status after X". It may instead be any of:

- aggregate multiple independent checks;
- recompute a global relationship from world state;
- verify a committed representation;
- verify an attestation;
- derive a classification from evidence.

This is a cross-cutting authority rule. It is **not** a request for a new
generic runtime field called `:stability-after`, `:semantic-authority`, or
similar. Each subsystem already has a domain-specific vocabulary for
derivation (below). Do not add a parallel status vocabulary unless a concrete
consumer demonstrably needs it.

This is also **not** about artifact stability. `STABILITY_MANIFEST.edn` /
`:support-status` track source-level stability surfaces for release hygiene.
They say nothing about whether a computed status is authoritative. Do not
conflate the two.

## Three assurance patterns

The derived-not-declared rule manifests in three concrete patterns. Use them
to classify any authority-bearing path.

1. **Composed-stage derivation** — a verdict is the conjunction of several
   independent, separately-fail-closed checks. *Example: evidence acceptance.*
2. **Authoritative-state / cross-object recomputation** — a global
   relationship is recomputed from authoritative world state rather than
   trusted from per-object declarations. *Example: settlement custody
   attribution.*
3. **Persisted-artifact integrity + semantic recomputation** — a committed
   representation is verified to recompute from the artifact, and verdicts
   are then derived from that verified evidence rather than copied from
   result-shaped fields. *Example: benchmark bundle/report after F1/F2.*

Positive-control shape ("good"):

```
supplied inputs / authoritative state
        ↓
independent checks / recomputation
        ↓
derived verdict
```

Anti-shape ("bad"):

```
supplied result-shaped field
        ↓
copied into authoritative output
```

The two confirmed exemplars below serve as positive controls when assessing
any problematic path.

## Allocation proof-assurance stages

The realized-allocation path uses the same derivation rule, but its stages must
not be collapsed:

1. **statement produced** *(implemented)* — the producer has data sufficient to
   build a `realized-allocation-statement.v1`;
2. **statement independently recomputed** *(implemented)* — canonical context,
   decision, and lifecycle rebuild the same six roots and statement root;
3. **scenario binding verified** *(profile-dependent)* — the domain-separated
   `scenario-realized-statement-binding.v1` relation recomputes for the exact
   scenario evidence-content and statement-collection roots under the
   registered proof profile;
4. **cryptographic computation verified** *(profile-dependent)* — a registered
   verifier confirms an SP1 proof for the pinned program/VK/profile and exact
   public statement (currently `largest-remainder-deferred-pro-rata.v1` only);
5. **fairness theorem satisfied** *(profile-dependent)* — the applicable
   Clojure theorem result is bound to that same statement root under the
   registered proof profile;
6. **activation authorized** *(not-yet-an-authoritative-boundary)* — a
   one-time authority transition accepts the same statement/result; and
7. **economic effect bound** *(not-yet-an-authoritative-boundary)* — the
   applied effect/custody transition commits the accepted allocation identity.

Legend: *(implemented)* = enforced unconditionally today; *(profile-dependent)*
= enforced only under a registered SP1 proof profile (the current profile is
deliberately narrow — see below); *(not-yet-an-authoritative-boundary)* = a
required architectural stage that is not yet an established authority boundary.

This list is an architectural requirement set, **not** an existing pipeline.
A later stage may rely on earlier verified stages, but never substitutes for
one. In particular, a valid statement root is not an SP1 proof, an SP1
computation proof is not a fairness theorem, and neither authorizes an
unbound economic effect.

The currently implemented proof profile is deliberately narrow:
`largest-remainder-deferred-pro-rata.v1`. It excludes effective caps, cap
redistribution, haircut, mixed fail actions, and unsupported rounding/policy
semantics from cryptographic admission until they have independent Rust/SP1
semantics. Such decisions are **uncovered**, not simplified into passes.

`:full-fill` is a recomputed settlement classification: every requested claim
is completely filled and no deferred or haircut amount remains. `fulfilled` is
only an immediately applied amount and may be partial. Thus a positive
fulfilled amount—or merely no deferred residual—is never proof of full fill.

## Where the rule is enforced

| Surface | Supplied value | Recompute gate | Authoritative result |
|---|---|---|---|
| Invariant aggregates | `:result` / `:passed`-style aggregate fields | `check-aggregate` recomputes from per-check results (`yield/invariants.clj:239`, `held_custody/legacy_validate.clj:697`, `review_aggregate_check.clj`) | pass/fail only from recomputed totals |
| Cancellation classification | base `:decision :classification` | `effective-decision-valid?` recomputes base → effective (`cancellation/operation.clj:235-248`) | `:authorized`, `:authorized-by-override`, `:forbidden` only; `:forbidden-authorized` is a notebook scenario label, never canonical |
| Cancellation operation statement | root-bearing operation fields / `:execution :status` | `operation-complete?` requires qualified SHA-256 references and cross-field status/effect consistency (`cancellation/operation.clj`) | a content-addressed, self-consistent statement only; it does **not** establish reference resolution, authority, admission, or authoritative commit |
| Certificate issuance | proposal `:result/status` | `issue-certificate` gates on `:passing` and forbids identity/attestation otherwise (`commands/allocation.clj:162-207`) | certificate `:status :valid` only when proof-backed; rejected proposals carry no signer attestation |
| Framing semantics | declared example labels | fail-closed `verify-stream`/`verify-single` accept only after canonical validity (`hash/framing_view.clj`) | example labels can never become canonical statuses |
| Canonical interpretation | user tags / intents | `resolve-intent` registry-only lookup; prefix-free domain-tag validation (`hash/canonical.clj:2305-2347`) | unknown intents fail closed |
| Expected outcomes | expected scenario results | expected/observed kept separate in the scenario runner; expectations never become observed outcomes (`scenario/runner.clj:123-147`) | pass/fail derives from observed only |
| Use-case registry | user-supplied registry path | explicit fail-closed loading only; never discovered from classpath/cwd (`use_cases/registry.clj`) | only explicitly-supplied, contained `:definition/ref` bundles load |
| Benchmark report | evidence `:metrics`, `:results`, `:claim-results` | `verify-evidence-bundle!` recomputes `:evidence/hash` (under the `:bundle-root` hash intent, BUNDLE_ROOT_V1) before any field is read (`benchmark/report.clj`, `benchmark/integrity.clj`) | `:all-pass?`, `:score`, `:claim/status`, `:conclusion` are integrity-bound only — verified ≠ framework-authoritative; provenance admission is separate |

### Cancellation statement boundary

`cancellation-operation.v1` is intentionally a pure canonical statement
contract. Its root binds operation fields and validates reference syntax, but
it does not load referenced artifacts, verify signatures or authority scope,
prove current-state eligibility, or establish that a mutation committed. Those
facts belong to the planned cancellation admission, commit-receipt, and
canonical-domain-fact boundaries. In particular, `:execution :status :applied`
in an operation statement is not by itself an authoritative committed status.

The legacy `:cancellation/authorised?` profile result remains available only for
compatibility. It means declared certificate-profile conformance and never
cryptographic or policy authority. New consumers must use
`:cancellation/certificate-profile-conforming?` for this limited model result
and reserve authorisation claims for verified admission evidence.

## Positive controls (confirmed exemplars)

Two surfaces were inspected and confirmed as derived-not-declared, fail-closed,
and non-falsifiable through the user-supplied use-case boundary. They are
treated as reference implementations for pattern 1 and pattern 2.

### Evidence acceptance — pattern 1 (composed-stage derivation)

- `acceptance-report` derives `:accepted?` from the **complete, normalized
  five-stage set** (`evidence/acceptance.clj:63-69`).
- Missing/nil stages explicitly fail with `:stage-missing` and `:valid? false`
  (`evidence/acceptance.clj:34-48`); there is no silent pass.
- No supplied acceptance verdict can substitute for stage validity.
- Content integrity and publisher/authenticity stages remain semantically
  distinct: content validity never implies publisher authenticity, and a
  tampered artifact yields `:content-hash-mismatch` regardless of other
  stages (`test/resolver_sim/evidence/acceptance_test.clj:42-61`).

### Settlement custody attribution — pattern 2 (authoritative-state recomputation)

- Attribution is recomputed from authoritative world state; nothing is copied
  from per-settlement declarations (`protocols_src/.../sew/invariants.clj`).
- Claimed adjustments must exist and bind the correct settlement.
- Custody delta must equal the filled amount.
- Claimed and observed adjustment sets must be complete/equal.
- The committed set root must match recomputation.
- Adjustment IDs must be globally unique across settlements —
  `:adjustment-double-attributed` is a **cross-settlement uniqueness check**,
  not an adjacency rule. Do not introduce an "adjacency" concept unless a
  separate explicit contract requires one.
- `:holds?` is derived from the absence of violations (`invariants.clj:1141`).

### Audit-matrix entries

| Surface | derived-not-declared | fail-closed | cross-object recomputation | user-use-case reachable | remediation |
|---|---|---|---|---|---|
| `acceptance-report` | yes | yes | — | no | none |
| `settlement custody attribution` | yes | yes | yes | no | none |

Neither path currently crosses the user-supplied use-case boundary, so both are
classified **non-falsifiable through that boundary** under the current
architecture.

## Benchmark F1/F2 against the exemplars

The corrected benchmark path is pattern 3 and must satisfy the same property
at two layers:

1. **Artifact integrity** — the persisted bundle recomputes to its committed
   `:evidence/hash` under the `:bundle-root` hash intent (`BUNDLE_ROOT_V1`).
   `verify-evidence-bundle!` (in `benchmark/integrity.clj`) selects exactly one
   scheme from the bundle's declared `:evidence/commitment-version` (absent ⇒
   current) and fails closed on any mismatch.
2. **Semantic authority** — report verdicts/statuses are derived from the
   verified underlying evidence, not copied from `:metrics`, `:results`,
   `:claim-results`, or summary fields. `build-report` runs the integrity gate
   before deriving `:all-pass?`, `:score`, `:claim/status`, `:conclusion`,
   `:scoring/classification` (`benchmark/report.clj`). Its output is
   integrity-bound only; framework-authoritative provenance additionally
   requires a separate, independently-derived publisher/provenance admission
   (see *Integrity vs. authenticity* in
   `docs/benchmarks/EVIDENCE_INTEGRITY_CONTRACT.md`).

This mirrors the exemplar "good" shape: runner recomputation of metrics and
claim results from scenario execution (authoritative state) → committed
artifact → integrity verification → derived verdict. The report layer does not
re-derive metrics from raw scenarios (that is pattern 2's job); its authority
comes from deriving verdicts off a verified commitment of the runner's
recomputed output. That boundary — verification before derivation — is what
keeps the report path derived-not-declared.

No concrete bypass was found in the corrected path at either layer. The
intended evidence-hash contract is confirmed as **hash-of-persisted-normalized
representation**; no material divergence was found, so the F1/F2 remediation
stands as-is. Neither acceptance nor Sew custody code is modified or broadened
beyond this documentation.

## Audit findings (2026-08)

Scope: the four named surfaces (consecutive concatenation, pro-rata fairness
end-to-end, forbidden/authorized/authorized-by-override, allocation
certificate issuance) plus the user-supplied use-case boundary and the
benchmark evidence/report surface.

### A. Recomputed (no gap)

- Allocation kernel recomputes roots, rate summary, assertions, and the
  result root from context rather than copying supplied values.
- `effective-decision-valid?` derives every effective decision
  classification from the base decision; an opaque merged label
  (`:forbidden-authorized`) is categorically refused.
- `issue-certificate` never issues identity or a signer attestation for a
  rejected proposal; existing regression
  `issue-certificate-fails-closed-for-rejected-proposal`
  (`test/resolver_sim/allocation/cli_test.clj:63`).
- Pro-rata fairness is evaluated by closed-form checks from scenario runs;
  its ordinary claim result is explicitly `:assurance/evidence`. The narrow
  proof-profile admission path independently recomputes statements and the
  scenario binding, but cryptographic computation remains fail-closed until a
  registered verifier-backed SP1 receipt exists. No ordinary `:pass` is
  labelled cryptographically proved.
- Consecutive concatenation fails closed: prefix-free domain-tag
  validation, registry-only intent resolution, and fail-closed framing
  verification.
- Scenario expected outcomes are kept structurally separate from observed
  outcomes and never feed pass/fail.
- Use-case registry loading is explicit and fail-closed; there is no
  implicit classpath discovery.

### B. Defects fixed in this audit

1. **Report trusted supplied result fields** (`benchmark/report.clj`
   `build-report`). `:all-pass?`, `:score`, `:claim/status :verified`,
   `:conclusion`, and `:scoring/classification` were derived directly from
   the evidence bundle's `:metrics` / `:claim-results` / `:results` with no
   integrity verification. Fix: `build-report` now calls
   `verify-evidence-bundle!` before reading any field
   (`benchmark/report.clj:414`) and fails closed on missing or
   non-recomputing `:evidence/hash`.

2. **Committed hashes did not recompute from the artifact.** The runner
   hashed the in-memory evidence map before write-time normalization, and
   `java.time.Instant` values serialized as non-portable `#object[...]`
   tags whose reloaded sentinel form hashed differently. `bb benchmark:verify`
   also could not read current bundles (plain `edn/read-string` crashed on
   `#object`). Fix: `normalize-runtime-values` canonicalizes `Instant →`
   ISO-8601 string (`benchmark/runner.clj:600`) and the committed
   `:evidence/hash` is computed over the normalized, persisted
   representation (`benchmark/runner.clj:541`); verification and report
   loading use the tolerant reader (`benchmark/integrity.clj:57`). Fresh
   runner output now has zero `#object` tags and verifies.

3. **Canonical fixtures were unverifiable.** Both fixture bundles carried
   stale committed hashes that failed `bb benchmark:verify`. Recomputed and
   repaired in place.

### C. Terminology boundaries (no code change)

- `:forbidden-authorized` remains a notebook admission-case label, not a
  canonical decision status.
- `STABILITY_MANIFEST`/`:support-status` remain artifact-stability
  bookkeeping, not semantic authority.
- No generic runtime `:stability-after` field was added; no consumer or
  enforcement point requires one.

### D. Missing tests added

- `test/resolver_sim/benchmark/integrity_test.clj`: runner-committed hash
  round-trips through `write-evidence` and verifies; tampered `:metrics`
  fails verification; missing `:evidence/hash` fails the gate; explicit
  `:evidence/commitment-version` selects exactly one scheme (`bundle-root.v2`
  ⇒ current, `bundle-root.v1` ⇒ legacy-v1); a declared version that does not
  match is rejected with no cross-scheme fallback; version-less bundles default
  to current; legacy sentinels are preserved by the tolerant reader.
- `test/resolver_sim/benchmark/report_test.clj`: `build-report` fails closed
  on missing hash, tampered `:metrics`, and tampered `:claim-results`.

### E. Known limitations

- **Authority ceiling (integrity, not authenticity).** `bb benchmark:verify` /
  `build-report` establish integrity only. A re-committed bundle whose
  `:evidence/hash` recomputes is internally consistent but is **not**
  framework-authoritative: a party that can recompute the hash can pass
  integrity while carrying attacker-selected (or re-selected) content, and can
  satisfy signature validity by signing with an arbitrary key and supplying an
  arbitrary `:evidence/public-key-path`. Framework-authoritative provenance
  requires a separate, independently-derived publisher/provenance admission —
  signer authentication **and** authorization of that signer from a trusted
  registry/policy, never bundle-supplied key material. The reusable invariant is:
  cryptographic signer identity + externally-rooted trust registry +
  policy-derived authorization for this role/scope = publisher/provenance
  admission. `evaluate-envelopes` (`evidence/finalization_signing.clj`) is a
  positive-control realization of this invariant for *finalization* evidence;
  the benchmark evidence path does not yet route `:evidence/signature` through an
  admission boundary of this shape. The future `admit-report` boundary is
  prescribed to reuse the trusted-registry/policy semantics directly — the
  `evaluate-envelopes` primitives where their envelope / role / scope /
  revocation abstraction genuinely fits, otherwise the lower-level trusted-registry
  mechanism — and must NOT hard-wire the finalization-domain `evaluate-envelopes`
  function by name (that would couple two authority domains prematurely).
  `build-report` therefore emits integrity-bound conclusions until that admission
  boundary is consumed. (See also `docs/benchmarks/EVIDENCE_INTEGRITY_CONTRACT.md`,
  *Integrity vs. authenticity*.)
- **Scheme selection is version-strict.** Legacy-v1 bundles verify only when
  they declare `:evidence/commitment-version "bundle-root.v1"`; the verifier
  does **not** fall back across schemes to make a hash match. Version-less
  bundles are interpreted as `bundle-root.v2` (the current scheme).
- Legacy `#object[...]` bundles (pre-normalization) are readable via the
  tolerant reader but cannot verify unless repaired to declare a
  `:evidence/commitment-version`; they are never admitted as authoritative by
  `build-report` because their committed hash does not recompute.

### F. Non-findings

- The use-case registry's explicit-path loading, cancellation classification,
  certificate gating, and framing verification were already fail-closed; no
  change needed.

## Regression guarantees

1. A supplied strategic-claim `:pass` cannot be forced into an
   authoritative claim result without recomputation.
2. Cancellation labels cannot be forced onto effective classifications.
3. Certificate issuance cannot be forced for rejected proposals.
4. Aggregates are independently recomputed where authoritative.
5. Framing semantics cannot be altered by declaration.
6. Example labels cannot become canonical statuses.
7. A rejected proposal cannot acquire identity or attestation.
8. Expected outcomes stay expectations; observed outcomes stay observed.
9. Use-case bundles are never implicitly loaded.
10. A benchmark evidence bundle confers no report authority until its
    committed hash recomputes (`verify-evidence-bundle!`).
11. An acceptance verdict requires every stage valid; a missing/nil stage is
    an explicit `:stage-missing` failure, never a pass
    (`evidence/acceptance.clj`).
12. A settlement's custody attribution must recompute from world state; a
    double-attributed adjustment is a cross-settlement violation
    (`protocols_src/.../sew/invariants.clj`).

## Related

- `docs/benchmarks/EVIDENCE_INTEGRITY_CONTRACT.md` — the `:bundle-root`
  commitment contract between writer, verifier, and report renderer.
- `src/resolver_sim/evidence/acceptance.clj` — pattern-1 exemplar
  (composed-stage acceptance derivation).
- `protocols_src/resolver_sim/protocols/sew/invariants.clj` — pattern-2
  exemplar (settlement custody attribution recomputation).
- `config/architecture/content-authority.edn` — content classification;
  `:known-missing-extension-points` records rootzones/hash-intents as
  hardcoded, which is part of the trust boundary protecting canonical
  interpretation.
- `docs/STABILITY.md` / `STABILITY_MANIFEST.edn` — artifact stability
  (distinct from the rule above).
