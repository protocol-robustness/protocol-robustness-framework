# Evidence Bundle Integrity Contract

The `:evidence/hash` field (computed under the `:bundle-root` hash intent,
`BUNDLE_ROOT_V1`) is the single source of truth for benchmark evidence
integrity. This document describes the writer → verifier → report contract and
the fail-closed gate that protects it.

## The committed identity (single canonical field)

The **single committed identity** of a benchmark evidence bundle is
`:evidence/hash`. There is no second commitment field. `:bundle-root` is the
hash-intent name (`hc/hash-with-intent {:hash/intent :bundle-root}`, domain tag
`BUNDLE_ROOT_V1`) — it is an alias for the projection used to compute
`:evidence/hash`, not a separate authority. The surface names
`:bundle-root` / `:bundle-root-hash` (in the run package index and finalization
record) all denote this same value; consumers MUST trust only
`:evidence/hash` after recomputation via `verify-evidence-bundle!`.

The runner commits `:evidence/hash` on every evidence bundle produced by
`bb benchmark:run`:

```
:evidence/hash = hash-with-intent {:hash/intent :bundle-root}
                                (into (sorted-map)
                                      (hashable-evidence evidence))
```

where `hashable-evidence` (`benchmark/integrity.clj`) selects the projected map
(it excludes post-hash/signature and operational fields: `:timestamp`,
`:evidence/hash`, `:evidence/signature`, `:evidence/public-key-path`,
`:benchmark/artifact-index`, `:repo`, `:run/manifest`/`:manifest/at`, and
`:results`/`:scenario/artifacts`), and `hash-with-intent` then applies
`project-world-to-structure-view` to normalize runtime values (functions →
`{:type :fn}`, `java.time.Instant` → ISO-8601 string, sets → sorted vectors,
ratios/floats → tagged maps) before SHA-256 over `BUNDLE_ROOT_V1`.

A current bundle therefore contains **zero** `#object[...]` tags: every runtime
value is normalized into a portable form before hashing and writing.

`:evidence/commitment-version` is deliberately **not** excluded: when present
it is committed into `:evidence/hash`, binding the bundle to the commitment
scheme chosen to interpret it. Version-less historical bundles omit the field,
so their hash is unchanged.

**Normative (commitment binding):** an evidence commitment binds both the
evidence and the commitment semantics used to interpret it. No consumer may
reassign a bundle's `:evidence/commitment-version` and rely on the same
`:evidence/hash` — a changed scheme recomputes to a different hash and fails
the gate.

**Normative (authority boundary):** a recomputed `:evidence/hash` establishes
integrity-authoritative evidence relative to this artifact only; it does not
establish framework-authoritative provenance. See *Integrity vs. authenticity*.

## The verification gate

`verify-evidence-bundle!` (in `benchmark/integrity.clj`, backed by
`verify-bundle-hash`) recomputes the commitment and fails closed:

- missing `:evidence/hash` → throw (`:reason :missing-evidence-hash`)
- unsupported `:evidence/commitment-version` → throw
  (`:reason :unsupported-commitment-version`)
- recomputed hash ≠ committed hash → throw
  (`:reason :computed-hash-mismatch`)

**Scheme is selected by declared version, never opportunistically.** A bundle
declares its scheme via `:evidence/commitment-version`:

- `"bundle-root.v2"` → current scheme (hash over `hashable-evidence` as-is;
  includes `:run/manifest` and `:benchmark-certification`)
- `"bundle-root.v1"` → legacy-v1 scheme (hash with `:run/manifest` and
  `:benchmark-certification` removed)
- absent → defaults to **current**

One artifact version → one unambiguous commitment rule. The gate does **not**
fall back from the current scheme to the legacy scheme (or vice versa) to make a
hash match — a bundle whose declared scheme does not recompute is rejected.
Legacy-v1 bundles must be migrated to carry `:evidence/commitment-version
"bundle-root.v1"`; version-less bundles are interpreted as current (the
historical primary scheme).

## Reading bundles

Current and legacy bundles may contain `#object[...]` tags (legacy only).
`read-evidence-bundle` (benchmark/integrity.clj:57) uses a tolerant reader
that converts such tags into inert legacy sentinel maps
(`{:legacy/runtime-object true …}`), never into executable objects. Detect
them with `legacy-object?`.

## Consumers

| Consumer | Behavior |
|---|---|
| `bb benchmark:verify` (cli.clj:531) | tolerant read → `verify-bundle-hash` → prints result, exit 0/1 |
| `bb benchmark:hash-only` (cli.clj:548) | tolerant read → recomputes current-scheme hash |
| `report/build-report` (report.clj:403) | **fail-closed integrity gate**: `verify-evidence-bundle!` before reading any field; output is integrity-bound only — provenance admission is a separate stage (see *Integrity vs. authenticity*) |
| `report/resolve-report` | delegates to `build-report`; same gate |

Because `build-report` verifies first, a supplied `:metrics`, `:results`, or
`:claim-results` value can never falsify `:all-pass?`, `:score`,
`:claim/status :verified`, `:conclusion`, or `:scoring/classification`.

## Integrity vs. authenticity

The authority pipeline for a benchmark evidence bundle is staged, and each stage
confers a distinct class of assurance:

```
execution/recomputation
        → committed bundle (:evidence/hash)
        → integrity verification (verify-evidence-bundle!)
        → [AUTHORITY CEILING: integrity-bound conclusion only]
        → (separately, for framework-authoritative evidence)
            → signer authentication
            → independent signer authorization / publisher admission
            → framework-authoritative evidence
```

`verify-evidence-bundle!` (and `bb benchmark:verify`) recompute the committed
`:evidence/hash` over the artifact. This is an **integrity** check: it proves
the bundle is internally consistent with the hash it declares. It is **not** an
authenticity check: a party that can recompute the hash can re-commit a tampered
bundle that still verifies. Concretely, an attacker that can produce the bundle
can recompute `:evidence/hash`, sign it with an arbitrary key, and supply an
arbitrary `:evidence/public-key-path` — this satisfies *signature validity* but
**not** signer authorization.

**Authority ceiling today:** `report/build-report` (report.clj) enforces the
integrity gate (`verify-evidence-bundle!`) before deriving `:all-pass?`,
`:score`, `:claim/status`, or `:conclusion`, and therefore its output is
**integrity-bound only**. Integrity verified ≠ provenance admitted
≠ framework authoritative. Report consumers MUST NOT treat report conclusions
as authoritatively-sourced unless a separate, independently-derived provenance
admission is established.

**Framework-authoritative provenance** additionally requires publisher admission:
signer authentication **and** independent authorization of that signer as an
evidence producer for the relevant role/scope, derived from **trusted
configuration/registry** — never from bundle-supplied key material. The reusable
invariant is:

```
        cryptographic signer identity
        +
        externally rooted trust registry
        +
        policy-derived authorization for this role/scope
        =
        publisher / provenance admission
```

`evaluate-envelopes` (`evidence/finalization_signing.clj`) is a positive-control
realization of this invariant for *finalization* evidence (pinned
`trusted-registry` + `policy`). The benchmark evidence path does not yet route
`:evidence/signature` through an admission boundary of this shape.

**Future implementation requirement (not this pass):** an `admit-report` /
`build-authoritative-report` boundary — or acceptance of a pre-derived admission
artifact — that realizes the invariant above. It MUST reuse the trusted-registry /
policy authorization semantics directly: the `evaluate-envelopes` primitives
**where their envelope / role / scope / revocation / policy-context abstraction
genuinely fits** for benchmark evidence, **otherwise extract or reuse the
lower-level trusted-registry authorization mechanism**. It must NOT hard-wire the
finalization-domain `evaluate-envelopes` function into benchmark evidence
admission merely because it currently has the right security property — that
would couple two authority domains prematurely.

The invariant this future boundary must enforce is non-negotiable:

> No consumer may elevate an integrity-bound conclusion to framework-authoritative
> evidence without a separate independently-derived provenance admission.

`build-report` itself stays useful for locally generated research, unsigned
exploratory evidence, historical fixtures, and user-owned experiments at the
integrity-bounded ceiling; authenticity policy is not turned into a universal
requirement for an otherwise useful report renderer.

## How to check

```
bb benchmark:run <benchmark-id> --run-root <dir>
bb benchmark:verify <dir>/benchmark/evidence/evidence.edn   # Hash match: yes
bb benchmark:hash-only <dir>/benchmark/evidence/evidence.edn
```

## Related

- `docs/architecture/STABILITY_AFTER.md` — the architectural rule this
  contract enforces (derived statuses, not declared statuses).
- `config/architecture/content-authority.edn` — content classification.
- `docs/STABILITY.md` — artifact (source) stability, distinct from the
  integrity contract above.
