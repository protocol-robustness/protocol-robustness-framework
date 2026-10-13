# Registry Admission V1 — Frozen Semantics

## Boundary

A registry candidate is a content-addressed projection of a sealed deployment and does not itself establish registry authority.

Registry admission evaluates whether a candidate may occupy a specified binding for a specified canonical scope under a specified immutable policy.

Admission decisions and reasons are derived outputs and must be reproducible from their committed inputs.

A protocol registry release contains only admitted bindings and is immutable and content-addressed.

Registry releases describe selections; they do not maintain mutable protocol lifecycle state and do not assert present-tense on-chain state.

Mutable protocol facts are introduced only by later block-pinned chain-state projections and claims.

## Core proposition

> Candidate `C` may occupy binding `B` for scope `S` under policy `P`.

The canonical semantic chain is:

```text
sealed deployment manifest
        ↓ verify
registry-binding candidate C
        + binding B
        + canonical scope root S
        + immutable policy P
        ↓
registry-admission evaluation
        ↓ admit | reject
protocol-registry release
```

The Deployment Ledger candidate is input evidence. It does not establish a registry binding, a scope, selection, admission, or active state.

## Scope identity

Scope must have canonical identity from v1 even while it remains a nested canonical value rather than a persisted top-level artifact.

```clojure
{:scope/type :program-cohort
 :scope/value {:program :enkel-accelerator
               :cohort 21}}
```

Every admission request and release binding commits `:scope/root`. The binding identity is therefore `(:binding/id, :scope/root)`, not merely `:binding/id`.

## Canonical artifacts

### `prf/registry-binding-candidate.v1`

A PRF-native canonical projection of a Deployment Ledger candidate and sealed manifest. It verifies/recomputes the input candidate root, verifies the referenced manifest, and rejects manifest, address, chain, artifact, or runtime-code substitutions. It retains `:local-only?` explicitly.

It contains no registry scope, `:active` state, guardian, module, admin, or other current chain state.

### `prf/registry-admission-policy.v1`

A closed declarative policy vocabulary. V1 evaluates only canonical candidate/deployment facts:

- binding permission;
- permitted scope shape/type;
- chain ID;
- logical deployment identity, required through `:allowed-logical-ids` for every binding rule;
- sealed-manifest and candidate/manifest consistency;
- explicit `:local-only?` permission;
- optional artifact/runtime constraints where required.

Policies must not execute arbitrary Clojure predicates or query current chain state.

### `prf/registry-admission-evaluation.v1`

The identity commits candidate root, policy root, binding ID, and scope root. Decision, checks, and stable reason keywords are derived and reconstructable—not caller-authoritative inputs.

V1 reason identifiers include:

```clojure
:candidate-root-invalid
:deployment-manifest-missing
:deployment-manifest-invalid
:candidate-manifest-mismatch
:binding-not-permitted
:scope-type-not-permitted
:scope-value-not-permitted
:chain-not-permitted
:logical-id-not-permitted
:local-only-not-permitted
```

### `prf/protocol-registry-release.v1`

An immutable content-addressed release binds:

```clojure
{:registry/id ...
 :registry/parent-root nil
 :scope-root ...
 :admission-policy-root ...
 :bindings [{:binding/id ...
             :scope/root ...
             :candidate-root ...
             :deployment-manifest-root ...
             :admission-evaluation-root ...}]}
```

Release validation requires an admitted evaluation for every binding and verifies candidate, scope, binding, policy, and registry identity agreement. `:registry/parent-root` must be `nil` or a canonical root; parent resolution remains deliberately deferred. Duplicate `(:binding/id, :scope/root)` pairs are invalid. Parent roots support immutable lineage; sequence numbers and semantic timestamps are intentionally excluded from v1.

## Frozen V1 contract

The following semantic chain is stable:

```text
candidate
  → sealed deployment manifest
  → canonical candidate root

scope
  → canonical scope root

policy
  → registry identity
  → binding rules
  → scope rules
  → chain rules
  → logical-id rules
  → local-only rule

evaluation
  → candidate root
  → policy root
  → registry id
  → binding id
  → scope root
  → derived checks/reasons/decision

release
  → registry id
  → optional parent root
  → admitted bindings only
  → candidate/evaluation/policy/scope/binding agreement
```

`admission.clj` is not the extension point for later protocol-state semantics. Later work must introduce separate artifacts and namespaces.

## Next boundary: block-pinned observations

The next vertical slice is a separate `registry-chain-observation.v1` boundary:

```text
protocol-registry-release.v1
        + registry binding
        + block identity
        + projection definition
        ↓
registry-chain-observation.v1
```

A chain observation is block-relative and must not be confused with registry selection. Projection definitions remain separate content-addressed artifacts from observations. An observation binds a projection-definition root, registered candidate, block identity, and observed result root.

Projection-specific definitions may describe governance, yield-module, or shortfall state, but the registry framework must not embed generic guardian/module/owner concepts.

Claims and attestations remain later layers over observations.

## Integration priority

The implementation order is explicitly:

```text
1. finish the pro-rata production contract path
2. close generic Registry Observation V1
3. add pro-rata-backed observation fixtures and integration
4. resume Sew-specific observation fixtures and contract work
```

No new Sew-specific contract implementation or Sew-specific Registry Observation fixture should be started while the pro-rata contract slice remains incomplete. Generic observation hardening may continue because it is protocol-neutral, but the first concrete protocol integration must use pro-rata assurance/reserve/allocation artifacts.

The first pro-rata observation fixtures should exercise release-to-binding resolution, candidate and block identity, projection-definition binding, and canonical result roots over source commitments, allocation roots, reserve state, or equivalent pro-rata facts. Sew guardian, yield-module, and BaseEscrow lineage fixtures remain later coverage.

## Explicit exclusions

V1 does not add mutable `:active` state, lifecycle/status fields, selection timestamps, generic categories/tags, chain-state projections, claims, attestations, or registry query databases.

Infrastructure may be reused from existing PRF systems—canonical hashing, content-root validation, dependency resolution, and reason reconstruction—but evidence-package admission domain semantics must not be adapted for registry admission.
