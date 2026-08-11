# Evidence Bundle Integrity Contract

The `:bundle-root` commitment is the single source of truth for benchmark
evidence integrity. This document describes the writer → verifier → report
contract and the fail-closed gate that protects it.

## The commitment

The runner commits `:evidence/hash` on every evidence bundle produced by
`bb benchmark:run`:

```
:evidence/hash = hash-with-intent {:hash/intent :bundle-root}
                                (normalize-runtime-values
                                 (dissoc evidence :timestamp))
```

Two properties matter:

1. **Hash over the persisted representation.** The hash covers the
   *normalized* evidence map — the same representation `write-evidence`
   serializes to disk — so the committed value can be recomputed from the
   artifact. Hashing the raw in-memory map would silently diverge once
   normalization rewrites runtime values.

2. **No runtime objects.** `normalize-runtime-values` (runner.clj:577)
   canonicalizes runtime values into portable forms before hashing and
   writing: functions → `{:type :fn :class …}`, yield-module maps →
   `describe-module`, and `java.time.Instant` → ISO-8601 string
   (runner.clj:600). A current bundle contains **zero** `#object[...]` tags.

## The verification gate

`verify-evidence-bundle!` (benchmark/integrity.clj:96) recomputes the
commitment and fails closed:

- missing `:evidence/hash` → throw (`:reason :missing-evidence-hash`)
- recomputed hash ≠ committed hash → throw
  (`:reason :computed-hash-mismatch`)

The gate accepts both the current scheme and the legacy pre-v2 scheme whose
hash excluded `:run/manifest` and `:benchmark-certification` (legacy bundles
that still verify remain readable).

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
| `report/build-report` (report.clj:403) | **fail-closed**: `verify-evidence-bundle!` before reading any field |
| `report/resolve-report` | delegates to `build-report`; same gate |

Because `build-report` verifies first, a supplied `:metrics`, `:results`, or
`:claim-results` value can never falsify `:all-pass?`, `:score`,
`:claim/status :verified`, `:conclusion`, or `:scoring/classification`.

## Integrity vs. authenticity

`bb benchmark:verify` recomputes the committed `:bundle-root` over the
artifact. This is an **integrity** check: it proves the bundle is internally
consistent with the hash it declares. It is not an authenticity check: a
party that can compute the public hash can re-commit a tampered bundle that
still verifies. Admission of an evidence bundle as a framework-authored
artifact is a separate decision made at the point of capture/attestation
(signing, `:evidence/signature`, chain registration). The report renderer
defends integrity; it does not authenticate authorship.

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
