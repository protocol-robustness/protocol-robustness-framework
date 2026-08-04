# Conformance Framework — Compatibility and Versioning Policy

Status: adopted for `:conformance/core-version 1` before the first change.
Migration utilities remain deferred (see maturity record); this policy defines
how changes will be classified when they arrive, so classification is never
invented reactively.

## Change classes

Every change to the conformance framework MUST be classified into exactly one
of the classes below.  The classification determines the version impact.

| Class | Effect | Requires |
|---|---|---|
| compatible | No root, claim, or verification-behavior change | — |
| new minor version | New optional/informational capability, no existing behavior change | New issue code or optional field; document |
| new envelope version | Committed fields of an envelope change but remain backward-verifiable | New schema version, envelope migration record, historical-verification tests |
| new profile version | A profile's declared surface changes | New profile version string; old version still committed |
| breaking core version | Any root, canonicalisation, claim prerequisite, or signature preimage changes | `:conformance/core-version` bump, new release artifact, full re-pinning |

## Change classification table

| Change | Class | Notes |
|---|---|---|
| Adding optional informational fields | compatible | MUST NOT enter any root |
| Adding a new issue code | compatible | Old codes unchanged |
| Adding a new profile | new minor version | Must pass profile validation |
| Adding implementations to an **experimental** registry | new minor version | Experimental entries MUST NOT enter committed registry roots |
| Adding implementations to the production registry | new envelope version | Changes registry root; re-pin and re-release |
| Modifying canonical committed fields | breaking core version | Changes roots |
| Changing claim prerequisites | breaking core version | Changes what is claimable |
| Changing canonicalisation (canonical JSON rules) | breaking core version | `canonicalisation/id` MUST change |
| Changing signature preimages or domains | breaking core version | Invalidates all signatures |
| Changing supported algorithms | breaking core version | Closed algorithm registry change |

## Rules

1. **Root stability**: a committed root MUST NOT change except under a
   `breaking core version` classification with a new release artifact.
2. **Canonicalisation identity**: any change to canonical JSON rules MUST
   introduce a new `canonicalisation/id` and MUST be rejected by
   `unsupported-canonicalisation` under the old verifier.
3. **Backward verification**: every new version MUST retain historical-
   verification tests proving old bundles still verify under their committed
   environment (see `historical-verification-test`).
4. **Golden re-pinning**: changing a root requires re-pinning every golden
   constant that binds it and regenerating the release artifact; the old
   constants MUST be preserved in the migration record.
5. **Signatures**: revocation is prospective by default; only a policy with an
   explicit `:key/status-effective-at` at or before the signing time rewrites a
   historical signature decision.
6. **Deferred promotion**: receipt DAG, named coverage dimensions, symmetric
   comparison, and schema migration MUST NOT be introduced unless their
   committed promotion triggers in `etc/conformance/maturity.edn` are met.
7. **Unsupported versions fail closed**: an unknown envelope, bundle, or
   profile version MUST produce a typed non-claimable result; a verifier MUST
   NOT guess.

## Version identifiers

- `:conformance/core-version` — integer; bumped only on breaking core changes.
- Envelope schema versions — `conformance.<envelope>/vN`; bumped on envelope
  version changes.
- Profile versions — `sew-trace-equivalence.v1` etc.; bumped on profile changes.
- Release id — `conformance-core-<major>.<minor>.<patch>`; the release artifact
  is the single verifiable subject for a version.
