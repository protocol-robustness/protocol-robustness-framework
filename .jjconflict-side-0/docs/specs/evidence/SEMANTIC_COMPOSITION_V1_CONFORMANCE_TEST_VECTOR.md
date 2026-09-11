Semantic Composition V1 — Conformance Test Vectors

Overview
This document defines conformance test vectors for `semantic-composition.v1` (Phase 2A authoritative constructor). Each vector specifies a composition input (a map of composition fields, excluding `:semantic-composition/root` which is derived), the expected canonical projection, and the expected composition root.

Conformance rule: A conforming implementation MUST accept all vectors marked "valid" and reject all vectors marked "invalid". The composition root for each valid vector MUST match the `expected_root` field exactly (string equality, including the `sha256:` prefix).

Vector format (EDN):

    {:vector/id :symbol
     :vector/description "human-readable description"
     :input {:semantic-composition/schema "semantic-composition.v1"
             :semantic-composition/version 1
             ... ;; projection fields (root excluded) ...}
     :classification :valid | :invalid
     :violation/id :optional/violation-keyword ;; present when classification = :invalid
     :expected-root "sha256:<64-hex>"} ;; present when classification = :valid

The root is computed as:
    ROOT = sha256-ref(SHA256(UTF8("SEMANTIC_COMPOSITION_V1") || canonical-bytes(projection(composition))))

Where `projection` selects the 12 projection fields (excluding `:semantic-composition/root`), and `canonical-bytes` follows the type-tagging rules in CANONICAL_HASH_SPEC_V1.

---

Vector 1: Minimal non-authoritative composition (legacy compatible)

    :vector/id :minimal-production-plain
    :vector/description "A minimal production-plain composition with no force-auth modules. This is the legacy-compatible baseline."
    :classification :valid
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-plain
            :semantic-composition/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            :semantic-composition/resolution {:extensions/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                                              :extensions/packages {}
                                              :extensions/capabilities {}}
            :semantic-composition/packages []
            :semantic-composition/capabilities []
            :semantic-composition/action-modules []
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}}
    :expected-root "sha256:PLACEHOLDER_COMPUTE_VIA_SEMANTIC_ROOT"

Note: The exact hex root for this vector MUST be computed by the reference implementation. Implementers SHALL run the vector through their implementation and record the resulting root. The root is stable for a given input — any change to any field produces a different root.

---

Vector 2: Production-governed composition with custody-execution

    :vector/id :production-governed-custody
    :vector/description "A production-governed composition with the custody-execution capability, activating force-authorisation modules and policy binding."
    :classification :valid
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-governed
            :semantic-composition/resolution-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            :semantic-composition/resolution {:extensions/resolution-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                                              :extensions/packages {[:extension/id :test/pkg :extension/version "1"] {:package/id :test/pkg :package-version "1" :package-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" :sealed true}}
                                              :extensions/capabilities {[:sew/force-authorisation :force-authorisation/custody-execution-v1] {:capability/kind :sew/force-authorisation :capability/id :force-authorisation/custody-execution-v1 :capability/version 1 :capability/contract-version 1 :capability/profile :production-governed}}}
            :semantic-composition/packages [{:extension/id :test/pkg :extension/package-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" :extension/version "1" :sealed true}]
            :semantic-composition/capabilities [["sew/force-authorisation" "force-authorisation/custody-execution-v1"]]
            :semantic-composition/action-modules [{:module/id :sew.module/force-authorisation-actions :module/version 1 :module/actions ["execute-force-authorised-action" "execute-force-authorized-action" "grant-consensus-force-authorisation" "grant-force-authorisation" "grant-force-authorization" "grant-related-claims-force-authorisation" "revoke-force-authorisation"] :module/state-regions [] :module/invariant-ids []}]
            :semantic-composition/state-region-modules [{:module/id :sew.module/force-authorisation-state :module/version 1 :module/actions [] :module/state-regions [:force-authorisations :force-authorisations/consumed :force-authorisations/consumption-records :next-force-authorisation-id] :module/invariant-ids []}]
            :semantic-composition/invariant-modules [{:module/id :sew.module/force-authorisation-invariants :module/version 1 :module/actions [] :module/state-regions [] :module/invariant-ids [:force-authorisations-governance-origin :force-authorisations-lifecycle-consistent]}]
            :semantic-composition/policy-bindings {:force-authorisation {:policy/root "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210" :issuance-assurance :governed-research-authority}}}
    :expected-root "sha256:PLACEHOLDER_COMPUTE_VIA_SEMANTIC_ROOT"

---

Vector 3: Invalid — wrong schema version

    :vector/id :invalid-schema-version
    :vector/description "A composition with an incorrect schema string MUST be rejected."
    :classification :invalid
    :violation/id :semantic-composition/invalid-schema
    :input {:semantic-composition/schema "semantic-composition.v0"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-plain
            :semantic-composition/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            :semantic-composition/resolution {}
            :semantic-composition/packages []
            :semantic-composition/capabilities []
            :semantic-composition/action-modules []
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}}
    :expected-root nil

---

Vector 4: Invalid — unknown field

    :vector/id :invalid-unknown-field
    :vector/description "A composition carrying a field outside the allowed set MUST be rejected."
    :classification :invalid
    :violation/id :semantic-composition/unknown-field
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-plain
            :semantic-composition/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            :semantic-composition/resolution {}
            :semantic-composition/packages []
            :semantic-composition/capabilities []
            :semantic-composition/action-modules []
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}
            :semantic-composition/unknown-field "must-reject"}
    :expected-root nil

---

Vector 5: Invalid — missing required field

    :vector/id :invalid-missing-field
    :vector/description "A composition missing a required field MUST be rejected."
    :classification :invalid
    :violation/id :semantic-composition/missing-field
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            ;; :semantic-composition/profile is MISSING
            :semantic-composition/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            :semantic-composition/resolution {}
            :semantic-composition/packages []
            :semantic-composition/capabilities []
            :semantic-composition/action-modules []
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}}
    :expected-root nil

---

Vector 6: Invalid — module mismatch (supplied modules diverge from derivation)

    :vector/id :invalid-module-mismatch
    :vector/description "A composition where supply modules do not match the canonical derivation from capabilities MUST be rejected."
    :classification :invalid
    :violation/id :semantic-composition/action-module-mismatch
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-governed
            :semantic-composition/resolution-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            :semantic-composition/resolution {:extensions/resolution-root "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                                              :extensions/packages {}
                                              :extensions/capabilities {[:sew/force-authorisation :force-authorisation/custody-execution-v1] {:capability/kind :sew/force-authorisation :capability/id :force-authorisation/custody-execution-v1 :capability/version 1 :capability/contract-version 1 :capability/profile :production-governed}}}
            :semantic-composition/packages []
            :semantic-composition/capabilities [["sew/force-authorisation" "force-authorisation/custody-execution-v1"]]
            :semantic-composition/action-modules [] ;; SHOULD be the FA action module set — mismatch
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}}
    :expected-root nil

---

Vector 7: Invalid — root mismatch

    :vector/id :invalid-root-mismatch
    :vector/description "A composition whose :semantic-composition/root does not match root(composition) MUST be rejected."
    :classification :invalid
    :violation/id :semantic-composition/root-mismatch
    :input {:semantic-composition/schema "semantic-composition.v1"
            :semantic-composition/version 1
            :semantic-composition/protocol "sew-v1"
            :semantic-composition/profile :production-plain
            :semantic-composition/resolution-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            :semantic-composition/resolution {}
            :semantic-composition/packages []
            :semantic-composition/capabilities []
            :semantic-composition/action-modules []
            :semantic-composition/state-region-modules []
            :semantic-composition/invariant-modules []
            :semantic-composition/policy-bindings {}
            :semantic-composition/root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}
    :expected-root nil

---

Phase 2C Verification Vectors

These vectors describe benchmark-level scenarios and the expected verifier behavior. They are consumed by `test/resolver_sim/benchmark/verify_test.clj`.

Vector 8: Legacy run — no composition declared

    :vector/id :legacy-no-composition
    :vector/description "Scenario input without :semantic-composition, finalization/completion with empty root. All Phase 2C checks MUST pass."
    :classification :valid
    :scenario-input {:scenario/id :s} ;; no :semantic-composition, no :execution-mode
    :expected-checks {:semantic-composition-root true
                      :authoritative-composition-presence true
                      :composition-root-derivation true
                      :final-ref true}

Vector 9: Authoritative run — root committed in scenario input

    :vector/id :authoritative-root-in-scenario
    :vector/description "Scenario input declares :semantic-composition/root = A, evidence/finalization/completion carry root A. All checks MUST pass."
    :classification :valid
    :scenario-input {:scenario/id :s
                     :semantic-composition {:schema "semantic-composition.v1"
                                            :semantic-composition/root "sha256:composition-A-root"}
                     :execution-mode :authoritative}
    :expected-checks {:semantic-composition-root true
                      :authoritative-composition-presence true
                      :composition-root-derivation true
                      :final-ref true}

Vector 10: Substitution attack — scenario A, evidence/finalization relabeled B

    :vector/id :substitution-A-evidence-as-B
    :vector/description "Committed scenario declares root A, but evidence/finalization/completion relabeled as root B (final_ref recomputed). composition-root-derivation MUST fail."
    :classification :invalid
    :violation/check composition-root-derivation
    :scenario-input {:scenario/id :s
                     :semantic-composition {:schema "semantic-composition.v1"
                                            :semantic-composition/root "sha256:composition-A-root"}
                     :execution-mode :authoritative}
    :expected-checks {:composition-root-derivation false
                      :final-ref false}

Vector 11: Downgrade attack — evidence root stripped from authoritative run

    :vector/id :downgrade-strip-evidence-root
    :vector/description "Scenario declares authoritative, but evidence :results stripped of :semantic-composition-root. authoritative-composition-presence MUST fail."
    :classification :invalid
    :violation/check authoritative-composition-presence
    :scenario-input {:scenario/id :s
                     :semantic-composition {:schema "semantic-composition.v1"
                                            :semantic-composition/root "sha256:abc123"}
                     :execution-mode :authoritative}
    :expected-checks {:authoritative-composition-presence false
                      :final-ref false}

Vector 12: Transplant attack — evidence A, finalization relabeled B

    :vector/id :anti-transplant-A-not-B
    :vector/description "Evidence carries root A but finalization/completion relabeled as root B. final-ref and authoritative-composition-presence MUST fail."
    :classification :invalid
    :violation/check final-ref
    :scenario-input {:scenario/id :s
                     :semantic-composition {:schema "semantic-composition.v1"
                                            :semantic-composition/root "sha256:composition-A-root"}
                     :execution-mode :authoritative}
    :expected-checks {:final-ref false
                      :authoritative-composition-presence false}}
