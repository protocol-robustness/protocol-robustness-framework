# Sensitivity Provenance Chain

## The core question

When you see a sensitivity classification on a report, how do you know:

1. That it genuinely came from the scenario inputs — not invented, not overridden?
2. Whether it was declared by an author (traceable to a person) or inferred by a classifier (traceable to a ruleset)?

We support both modes, and we make each one **cryptographically verifiable** by binding every link in the derivation chain into a tamper-evident hash tree.

---

## Two provenance modes

| Mode | Label | Meaning |
|------|-------|---------|
| **Declared** | `:sensitivity-status/evaluated` | An explicit `:scenario/sensitivity` block exists in the scenario definition. The report records `declaration-provenance` with the source path, content hash, and schema version. You know *which scenario said what*. |
| **Structural-only** | `:sensitivity-status/no-declaration-structural-only` | No declaration exists. The level was inferred by the classifier from the artifact's content. You know *that structural analysis was used* (source type), but not *which author declared it* (source identity) — because no author did. |

Both are equally verifiable. The distinction is honest: we don't pretend an author signed off when none did.

---

## How structural classification is deterministic

`sentinel/classify-structural` is a **pure function**. Given the same artifact content, it always returns the same structural level. No randomness, no secret state, no external dependency. A verifier with the same scenario bytes can independently recompute the classification and confirm it matches.

Scenarios are **content-addressed** — each carries `:scenario-input-hash` (raw bytes) and `:scenario-content-hash` (parsed content). The classification result is bound to these hashes, not to a file path or a run timestamp.

---

## The hash chain

Each layer commits to the layer below it:

```
Scenario inputs  ──scenario-hash──┐
                                  ▼
classify-structural()  ──── :structural-level
                                  │
Per-scenario entry ──────────────┤
  {:scenario-id, :structural-level,
   :effective-level, :input-hash}
                                  │
merge-sensitivity() ────────────┤
                                  │
Aggregation derivation ─────────┤
  {:aggregation/input-set-hash    ← hash of every scenario's
    = SHA256(canonical([          {id, declared-level,
      {id, dl, sl, el} ...]))      structural-level, effective-level}
                                  │
Sensitivity report ─────────────┤
  :report/semantic-hash           ← SHA256("EVIDENCE_RECORD_V1"
    = SHA256(domain-tag ||          || canonical-bytes(report
      canonical(report)))            minus volatile fields)
                                  │
Attestation bundle ─────────────┤
  :bundle/root-hash               ← SHA256("MANIFEST_V1"
    = SHA256(domain-tag ||          || canonical-bytes(manifest))
      canonical(manifest))
```

The critical invariant is the **`:aggregation/input-set-hash`**: a deterministic hash over every scenario's identity and its structural/declared/effective levels. Changing any scenario, its classification, or swapping one scenario for another breaks this hash — which breaks the report's semantic hash — which breaks the bundle root hash.

---

## What a verifier checks

Given the attestation bundle (a self-contained directory with the sensitivity report, evidence nodes, attestations, and registries):

1. Read the sensitivity report from its referenced path
2. **Verify the byte hash** — recompute SHA-256 of the file, compare to `:sha256` in the bundle ref
3. **Verify the semantic hash** — parse JSON, strip volatile fields, recompute `hash-with-intent :evidence-record`, compare to `:semantic-hash`
4. **Inspect per-scenario entries** — each records `:input-hash`, `:structural-level`, `:declared-level` (if present), `:effective-level`, and `:sensitivity/status`
5. **Recompute the aggregation** — take the per-scenario entries, run `merge-sensitivity`, confirm the run-level and winners match
6. **Confirm the input-set-hash** — recompute the canonical hash over all `{:scenario-id :declared-level :structural-level :effective-level}` tuples, compare to what's in the report
7. **(Optional) Re-run classification** — with the original scenario inputs, call `classify-structural` independently and confirm the structural level matches

Every check is a pure function against local data. No network calls, no trusted third parties.

---

## Key property

**"This sensitivity classification came from structural analysis of these scenario inputs, and no declaring author was involved — we can prove both facts simultaneously."**

The hash chain proves the classification was deterministically derived from the content-addressed inputs. The `:sensitivity-status/no-declaration-structural-only` flag honestly records that no author declaration existed. A downstream consumer can verify the derivation and make their own trust decision about structural analysis — they don't have to take anyone's word for what the classification is or where it came from.
