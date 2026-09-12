# Evidence Concept Overview

This document explains what **evidence** means in PRF, how the different evidence subsystems fit together, and where to find detailed documentation for each area.

## What Evidence Is

In PRF, evidence is the **deterministic, content-addressed record of what happened during a protocol simulation run**. It is not a log, not a notebook, and not a prose report — it is a machine-verifiable artifact that supports cryptographic verification, independent replay, and forensic review.

Every evidence artifact in PRF shares four properties:

1. **Deterministic** — the same inputs always produce the same evidence
2. **Content-addressed** — each artifact is identified by its canonical SHA-256 hash
3. **Immutable** — an evidence artifact is never modified after creation
4. **Verifiable** — the evidence chain can be checked for integrity, completeness, and authenticity

## Five Pillars of Evidence

The evidence infrastructure is organized into five interconnected subsystems:

### 1. Protocol Evidence Capture

The innermost layer. During scenario replay, every critical state transition produces an evidence record. Two capture paths exist:

| Path | Entry point | Scope | Chain cursor |
|------|-------------|-------|--------------|
| **Targeted protocol evidence** | `capture-event-evidence!` in `io/event_evidence.clj` | Mechanism-specific fields (escrow-created, dispute-raised, slashing, etc.) | Yes — `chain-seq`, `chain-prev-hash`, `chain-self-hash` |
| **Generic transition traces** | `emit-evidence!` in dispatcher | Every event with before/after world hashes, attribution context | No — bypasses the chain cursor |

**Key files:**
- `src/resolver_sim/io/event_evidence.clj` — capture, persistence, normalization
- `src/resolver_sim/evidence/capture.clj` — finalization, hash computation

**Key docs:**
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` — full data flow, evidence type table

### 2. Evidence Chain & Registry

The evidence chain binds captured records into a **self-hashed, content-addressed artifact registry**. It has three levels of state:

**Scenario-local:** Each scenario runs inside `with-fresh-registry` / `with-fresh-chain-cursor`, creating an isolated Clojure atom. Evidence is written to disk as JSON files under `event-evidence/`. When the scenario completes, `register-scenario-snapshot!` captures the local registry and cursor.

**Run-level aggregation:** After all scenarios in a run, `accumulate-scenario-evidence!` merges every scenario-local snapshot into a top-level atom, deduplicating by `intent-hash=`. The resulting evidence-registry-atom contains index entries and transition-evidence entries.

**Persistent registry:** `finalize-and-write!` writes `evidence-registry.json`, which includes `:registry-hash` — a canonical hash committing to all artifacts.

**Validation:** `build-evidence-registry!` produces a read-only evidence registry with indexes and cross-links. The registry is always derived from existing artifacts, never modifies them, and is safe to delete and rebuild.

**Key files:**
- `src/resolver_sim/evidence/chain.clj` — registry atom, cursor, aggregation, reconciliation, aggregate cursor
- `src/resolver_sim/evidence/registry.clj` — registry builder, indexing, validation

**Key docs:**
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` — three levels, cursor model, reconciliation, data flow diagram
- `docs/architecture/EVIDENCE_REGISTRY.md` — registry schema, validation model, builder flow, phases
- `docs/evidence/EVIDENCE_CHAIN_PRODUCTION_PLAN.md` — known gaps, tier structure, production-readiness analysis

### 3. Evidence DAG

The evidence DAG is the **canonical researcher-facing evidence structure**. While the chain/registry captures and sequences evidence, the DAG organizes it as immutable, content-addressed nodes with explicit parent relationships, evidence hashes, result status, provenance, attestations, and policy-filtered presentation output.

The DAG answers four questions:
1. What exactly ran?
2. What evidence did it produce?
3. What earlier evidence does it depend on?
4. Can another runner, reviewer, or researcher verify the same chain of facts?

**Node structure:** Each node contains execution metadata, result metadata, evidence hashes (input and output), parent-hash links, bootstrap roots, attestations, and versioned extensions. Volatile fields (node-id, node-hash, timestamp, policy-output) are excluded from the canonical hash.

**Bundle root:** The run-level commitment that records the run request, registry snapshot, execution summary, and the DAG root node hash.

**Boundaries:** The canonical DAG stores immutable execution records, hash-addressed protocol artifacts, and explicit hash edges. It does not store debug logs, notebook output, report prose, or researcher notes.

**Key files:**
- `src/resolver_sim/evidence/node.clj` — DAG node construction, validation, indexing, navigation

**Key docs:**
- `docs/evidence/EVIDENCE_DAG_OVERVIEW.md` — full DAG model, forensic-grade features, researcher-facing navigation, derived views, validation rules
- `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md`
- `docs/specs/evidence/IDENTITY_ALGEBRA_SPEC_V1.md`
- `docs/specs/RUN_BUNDLE_ROOT_SPEC_V1.md`

### 4. Forensic-Grade Features

Evidence can be elevated to forensic grade through five criteria:

| Criterion | Mechanism | Verification |
|-----------|-----------|-------------|
| Registry hash integrity | Canonical SHA-256 over all artifacts | Recompute from disk, compare |
| Cryptographic signature | Ed25519 key signs registry hash | Verify against known public key |
| Chain cursor continuity | Cursor records chain tip, signs it | Verify cursor hash, signature |
| RFC 3161 timestamp | TSA binds hash to wall-clock time | Validate TSA response token |
| Evidence reconciliation | Disk ↔ registry ↔ cursor agreement | `reconcile-evidence!` check |

A forensic-grade chain satisfies all five criteria. The `src/resolver_sim/evidence/timestamping.clj` module supports both local self-signed timestamp proofs and RFC 3161 TSA requests.

**Key files:**
- `src/resolver_sim/evidence/timestamping.clj` — local TSA, RFC 3161 support
- `src/resolver_sim/evidence/chain.clj` — reconciliation, aggregate cursor

**Key docs:**
- `docs/architecture/FORENSIC_EVIDENCE.md`
- `data/concepts/assurance/evidence_chain.edn` — forensic concept model with roles, entities, outcomes, failure modes
- `docs/specs/ATTESTATION_SPEC_V1.md`
- `docs/specs/evidence/EVIDENCE_COMMITMENT_SPEC_V1.md`

### 5. Derived Views & Research Artifacts

Derived artifacts sit **downstream** of the canonical DAG and chain. They reference node hashes, episode paths, claim results, and bundle hashes but never replace or reinterpret the canonical root. Examples include:

- Mechanism persistence indexes
- Scenario matrices
- Evidence summaries and reports
- Claim reports
- Visual evidence packs
- Clerk notebooks that navigate the evidence corpus

**Key files:**
- `src/resolver_sim/evidence/summary.clj` — evidence summaries
- `src/resolver_sim/evidence/links.clj` — cross-linking and indexing

**Key docs:**
- `docs/evidence/EVIDENCE_DAG_OVERVIEW.md` (see "Derived Research Views" section)
- `docs/framework/evidence-invariant-mapping.md`

## Evidence Lifecycle

```
Protocol replay
      │
      ▼
capture-event-evidence! / emit-evidence!
      │
      ├──→ Event evidence files (event-evidence/*.json)
      │
      ▼
with-fresh-registry / with-fresh-chain-cursor
      │
      ├──→ Scenario-local registry (isolated atom)
      ├──→ Chain fields injected (chain-seq, prev-hash, self-hash)
      │
      ▼
register-scenario-snapshot!
      │
      ▼
accumulate-scenario-evidence!
      │
      ├──→ Deduplication by intent-hash
      │
      ▼
finalize-and-write!
      │
      ├──→ evidence-registry.json (self-hashed)
      ├──→ aggregate-cursor.json (run-level cursor)
      │
      ▼
reconcile-evidence! ↔ disk evidence files
      │
      ▼
Evidence DAG construction (node.clj)
      │
      ├──→ DAG nodes with parent edges
      ├──→ Bundle root commitment
      │
      ▼
Forensic sealing
      │
      ├──→ Ed25519 signature
      ├──→ RFC 3161 timestamp
      │
      ▼
Derived research views
```

## Evidence Types

The chain architecture documents the evidence types produced during protocol replay. These are the `:evidence/type` values emitted by `capture-event-evidence!` call sites in the Sew protocol:

| Type | Typical count | Category |
|------|--------------|----------|
| escrow-created | 185 | Escrow lifecycle |
| dispute-raised | 168 | Dispute lifecycle |
| escrow-released | 125 | Escrow lifecycle |
| escrow-refunded | 41 | Escrow lifecycle |
| stake-registered | 33 | Stake lifecycle |
| bond-posted | 27 | Bond lifecycle |
| slashing | 19 | Slashing |
| dispute-escalated | 19 | Dispute lifecycle |
| fraud-slash-proposed | 10 | Fraud |
| evidence-submitted | 9 | Dispute lifecycle |
| resolver-unavailability-changed | 8 | Resolver management |
| resolution-challenged | 8 | Dispute lifecycle |
| fraud-slash-appealed | 6 | Fraud |
| incentive-payout | 5 | Economics |
| escrow-withdrawn | 4 | Escrow lifecycle |
| reversed | 3 | Dispute lifecycle |
| fraud-slash-rejected | 2 | Fraud |
| prorata-allocation | 1 | Economics |
| guard-rejected | 1 | Security |
| rejected | 1 | Dispute lifecycle |
| stake-withdrawn | 1 | Stake lifecycle |

Beyond these, the evidence system also produces:
- **Generic transition traces** (every event, no chain-seq)
- **Diff evidence** (post-run structural comparison of before/after world state)
- **Invariant results** (pass/fail for specific invariant checks)
- **Validation roots** (artifact registry validations)
- **Evidence chain summaries** (reconstructed during registry build)

## Evidence Semantics

Evidence artifacts carry semantic metadata defined in `src/resolver_sim/definitions/registry.clj`:

| Dimension | Values | Purpose |
|-----------|--------|---------|
| **Status** | falsified, inconclusive, not-applicable, not-evaluated, not-falsified | What the evidence says about a claim |
| **Severity** | critical (4), high (3), medium (2), low (1) | How important the finding is |
| **Purpose** | adversarial-robustness, regression, theory-falsification, unclassified | Why the evidence was collected |
| **Confidence** | high (0.9), medium (0.6), low (0.3) | How sure we are |
| **Story family** | collusion, deadline-boundary, deflection, economic-solvency, scenario-deep-dive, theory-falsification, threat-detected | Narrative context |

**Key docs:**
- `docs/generated/evidence-semantics.md` — full reference of statuses, severities, purposes, confidences, story families
- `src/resolver_sim/definitions/registry.clj` — source of truth

## Invariant Mapping

Evidence from scenario replays maps to protocol invariants. The mapping from evidence IDs (used in Reference Validation Suite v1) to canonical simulator invariant IDs is maintained in:

**Key doc:** `docs/framework/evidence-invariant-mapping.md`

| Evidence ID | Canonical simulator IDs |
|-------------|------------------------|
| `active-escrow-module-snapshot-immutable` | `:module-snapshot-immutable` |
| `governance-forward-only` | `:module-snapshot-immutable`, `:escalation-level-monotonic` |
| `slashable-liability-preserved` | `:solvency`, `:bond-slash-bounded`, `:liability-slash-boundary`, `:conservation-of-funds` |
| `bounded-progress-under-load` | `:no-stale-automatable-escrows`, `:resolver-capacity` |
| `liability-gated-withdrawal` | `:no-withdrawal-during-dispute`, `:bond-liquidity`, `:held-delta-accounted` |
| `no-double-settlement` | `:single-resolution-payout-consistent`, `:cancellation-mutex`, `:pending-settlement-consistent`, `:terminal-states-unchanged` |
| `pull-first-value-flow` | `:settlement-principal-boundary`, `:settlement-yield-boundary`, `:liability-slash-boundary`, `:bond-boundary`, `:fee-boundary`, `:claimable-classification` |
| `escalation-layer-protection` | `:escalation-level-monotonic`, `:dispute-level-bounded`, `:dispute-resolution-path` |

## Evidence in the Wider Concept Model

The concept data files in `data/concepts/` define evidence at the stakeholder/assurance level:

- **`data/concepts/decision-quality/evidence.edn`** — Consensus evidence: what facts were available to the decision process. Roles (evidence-submitter, examiner, keeper), entities (submission, deadline, registry), failure modes (missing, contradictory, late, ignored).
- **`data/concepts/assurance/evidence_chain.edn`** — Forensic evidence chain: how evidence binds into a self-verifiable chain. Five forensic-grade criteria, roles (producer, verifier, signer, TSA), failure modes (tampered hash, broken signature, broken cursor, unverifiable timestamp, unreconciled).

The general glossary (`docs/concepts/GLOSSARY.md`) provides a quick-reference mapping of evidence, evaluation, and protocol terms.

## Guide to Evidence Documentation

| Document | Type | Audience |
|----------|------|----------|
| `docs/concepts/GLOSSARY.md` | General glossary | Everyone |
| `docs/concepts/EVIDENCE_OVERVIEW.md` | **This document** — conceptual overview | Everyone new to evidence |
| `docs/evidence/README.md` | Adversarial simulation evidence index | Researchers, reviewers |
| `docs/evidence/EVIDENCE_DAG_OVERVIEW.md` | DAG architecture, navigation, boundaries | Researchers, implementers |
| `docs/evidence/EVIDENCE_CHAIN_PRODUCTION_PLAN.md` | Production-readiness gaps, tiers | Engineers |
| `docs/evidence/RESEARCHER_EVIDENCE_PACK.md` | Reproducibility pack workflow | Researchers |
| `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` | Chain data flow, cursor, reconciliation | Engineers, implementers |
| `docs/architecture/EVIDENCE_REGISTRY.md` | Registry schema, builder, validation | Engineers |
| `docs/architecture/FORENSIC_EVIDENCE.md` | Forensic-grade criteria, signing, timestamping | Engineers, auditors |
| `docs/framework/evidence-invariant-mapping.md` | Evidence ↔ invariant ID mapping | Researchers, testers |
| `docs/generated/evidence-semantics.md` | Status, severity, purpose, confidence reference | Everyone |
| `docs/generated/evidence-artifact-contract.md` | Artifact schema contracts, required fields | Integrators |
| `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md` | DAG node specification | Implementers |
| `docs/specs/evidence/EVIDENCE_COMMITMENT_SPEC_V1.md` | Evidence commitment specification | Implementers |
| `docs/specs/evidence/EVIDENCE_POLICY_SPEC_V1.md` | Evidence policy specification | Implementers |
| `docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md` | Canonical hash algorithm specification | Implementers |
| `docs/specs/evidence/IDENTITY_ALGEBRA_SPEC_V1.md` | Identity algebra for evidence | Implementers |
| `data/concepts/decision-quality/evidence.edn` | Stakeholder-level evidence concept model | Concept modelers |
| `data/concepts/assurance/evidence_chain.edn` | Forensic assurance concept model | Concept modelers |
