# External clean-room replication — realized partial-fill v1

## Purpose

This protocol defines the first external assurance exercise for PRF pro-rata.
It tests independent native reconstruction from normative authority; it does
not test proof generation, on-chain admission, persistence, or current-chain
authority.

## Frozen semantic scope

| Dimension | Value |
|---|---|
| Profile | `largest-remainder-deferred-pro-rata.v1` |
| Subject | realized partial-fill |
| Statement | `realized-allocation-statement.v1` |
| Rounding | largest remainder |
| Shortfall action | deferred only |

The exercise inputs are:

- request set;
- available amount;
- allocation context;
- supported allocation policy;
- supported fail-action policy; and
- round/lifecycle context.

The implementation must derive:

- realized allocation rows;
- allocation and rounding witnesses;
- canonical intermediate bodies and bytes;
- every committed sub-root; and
- the final `realized-allocation-statement.v1` body and root.

The exact committed body projections, root preimages, ordering rules, and
witness boundary are defined in `realized-partial-fill-projections-v1.md`.

## Required agreement

A submission passes a case only when the following compare exactly, in order:

1. semantic result bodies;
2. witnesses;
3. canonical encodings;
4. committed sub-roots; and
5. the final statement root.

A final-root-only comparison is not sufficient evidence of replication.

## Explicit non-goals

This first exercise does **not** require:

- generated Solidity verifiers or on-chain execution;
- SP1 proof generation or proof receipt issuance;
- a distributed runner;
- cap redistribution, haircuts, mixed failure actions, or general probabilistic
  pro-rata; or
- claims about native write-back, authoritative admission, or chain-head state.

Those are later semantic-scope or assurance-depth milestones.

## Independence protocol

### Material released in Phase A

The contributor may receive only:

- the normative portable specification;
- schemas;
- domain-tag definitions;
- canonical serialization specification;
- public test vectors; and
- the acceptance interface and this protocol.

The contributor must not receive before the Phase B declaration:

- Clojure or Rust implementation sources;
- internal helper implementations;
- reference derivation traces;
- expected holdout bodies, bytes, roots, or verdicts; or
- holdout corpus contents.

Public expected values are development aids only. They are not authority inputs.

### Staged exercise

1. **Phase A — public development.** The contributor implements against public
   vectors and records all specification ambiguities.
2. **Phase B — completion declaration.** The contributor submits source and
   artifact identities, toolchain/dependencies, materials accessed, and public
   results. The semantic package is frozen at this point.
3. **Phase C — holdout-input release.** The authority holder’s private channel
   contains both the holdout inputs and the sealed reference expected-output
   artifacts, but releases **only** the holdout semantic inputs and output schema
   to the contributor. Expected bodies, bytes, roots, and verdicts remain
   inaccessible.
4. **Phase D — independent submission.** The contributor irrevocably fixes and
   submits all derived bodies, canonical bytes, roots, execution command, and
   implementation identity before expected values are opened.
5. **Phase E — comparison.** Only after the Phase D submission is fixed does the
   authority holder open the sealed reference expectations and compare the
   submitted data in the required-agreement order.
6. **Phase F — resolution.** Every divergence receives a written primary
   classification: `:spec-ambiguity`, `:reference-defect`,
   `:independent-implementation-defect`, `:canonicalization-defect`, or
   `:environmental-reproduction-failure`.

Do not alter expected holdout values to obtain agreement. A confirmed reference
or specification correction requires a new package release identity.

### Sealing record and immutability

The current engagement’s generator-source digest, projection-rules digest,
holdout-input root, and expected-output root in `holdout-commitment.edn` are
immutable evidence for this engagement. The generator/projection digests and
holdout-input root may be disclosed in the Phase B handoff record. The raw
expected-output root and fresh sealing nonce remain authority-private until
after the Phase D submission is fixed. Before then, the handoff exposes only
the nonce-blinded sealing commitment.

The sealing commitment is:

```text
SHA-256(
  "prf/cleanroom-seal.v1"
  || nonce
  || generator-source-digest
  || projection-rules-digest
  || holdout-input-root
  || expected-output-root)
```

Any change to one of these values—whether caused by a generator, projection,
input, expected output, or sealing nonce change—requires a new sealing record
and a new engagement identity; it must not silently amend this engagement.

## Current package status

The draft handoff manifest is at
`etc/conformance/cleanroom/realized-partial-fill-v1/manifest.edn`. It records
public inputs, the portable-contract release root, and only the blinded
reference sealing commitment. The package is not frozen: holdout inputs remain
private staging and reference expected outputs are sealed as
`:sealed-reference-ready`; the raw expected-output root and nonce are withheld
until after Phase D, and no package root is asserted. The
draft public corpus contains two semantically distinct cases: the original
available amount of 100 and a 75-unit partial-fill case. The acceptance runner
executes both positive cases and four explicit negative cases (missing context,
available exceeding total requested, unknown claim ID, and unsupported policy),
in addition to the malformed-input fail-closed check. Expected compact
projections are stored separately under `public-expected/` and
`public-negative-expected/`. Three valid in-scope holdout inputs now exist under the private staging
subdirectory. Their deterministic input-set commitment and the separately
sealed Clojure-reference expected-output commitment are recorded in
`holdout-commitment.edn`. The generator calls only
`persisted-statement-admission/reconstruct-input` and emits canonical bodies,
canonical bytes, roots, and per-output provenance; it does not invoke Rust.
Both inputs and expected outputs remain private staging material, so this is
not a formal public release.

## Completion claim

After a successful exercise, the defensible claim is:

> An external clean-room native implementation reconstructed the
> `largest-remainder-deferred-pro-rata.v1` realized partial-fill profile from
> the normative specification and inputs, reproducing semantic outputs,
> canonical encodings, committed sub-roots, and
> `realized-allocation-statement.v1` holdout commitments.

This does not establish SP1 proof validity, on-chain enforcement, unsupported
policy coverage, native realization, or authoritative state admission.
