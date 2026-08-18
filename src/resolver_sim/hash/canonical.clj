(ns resolver-sim.hash.canonical
  "Canonical hash implementation per CANONICAL_HASH_SPEC_V1
   and CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.

   This is the single authoritative hashing implementation for all
   evidence, world-state, manifest, and bundle hashing. All new code
   MUST use this namespace. The old resolver-sim.benchmark.hashing
   is deprecated.

   API:
     (validate-canonical-value! value)     — throws on unsupported types
     (canonical-bytes value)               — byte-array of typed encoding
     (hash-bytes bytes)                    — raw SHA-256 digest (32 bytes)
     (domain-hash domain-tag value)        — SHA-256(domain_tag || canonical_bytes), returns hex"
  (:refer-clojure :exclude [read-string])
  (:import [java.security MessageDigest]
           [java.io ByteArrayOutputStream]
           [java.math BigInteger BigDecimal])
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.reference :as hash-ref]))

(declare domain-hash out-of-domain! strip-self-hash-fields)

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Tags (per Binary Encoding ABI)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:const tag-null       (byte 0x00))
(def ^:const tag-bool-false (byte 0x01))
(def ^:const tag-bool-true  (byte 0x02))
(def ^:const tag-int        (byte 0x10))
(def ^:const tag-ratio      (byte 0x11))
(def ^:const tag-string     (byte 0x20))
(def ^:const tag-keyword    (byte 0x22))
(def ^:const tag-array      (byte 0x30))
(def ^:const tag-map        (byte 0x31))

;; ──────────────────────────────────────────────────────────────────────────────
;; Domain Tags (per Canonical Hash Spec V1)
;; ──────────────────────────────────────────────────────────────────────────────

(def domain-tags
  "Map of keyword domain identifiers to their ASCII domain tag strings.
   The domain tag is prepended to canonical bytes before hashing
   to prevent cross-domain hash collisions.

   NOTE: Maintained for backward compatibility with callers that
   pass keywords to domain-hash. Intent contracts now use strings
   directly via :intent/domain-tag."
  {:world-state     "WORLD_STATE_V1"
   :evidence-record "EVIDENCE_RECORD_V1"
   :evidence-chain  "EVIDENCE_CHAIN_V1"
   :evidence-chain-link-v1 "EVIDENCE_CHAIN_LINK_V1"
   :run-evidence-hash-set-v1 "RUN_EVIDENCE_HASH_SET_V1"
   :evidence-hash-set "EVIDENCE_HASH_SET_V1"
   :evidence-finalization-v2 "EVIDENCE_FINALIZATION_V2"
   :runner-finalization "RUNNER_FINALIZATION_V1"
   :run-package-index "RUN_PACKAGE_INDEX_V1"
   :merkle-leaf     "EVIDENCE_MERKLE_LEAF_V1"
   :merkle-node     "EVIDENCE_MERKLE_NODE_V1"
   :registry        "REGISTRY_V1"
   :manifest        "MANIFEST_V1"
   :provenance      "PROVENANCE_V1"
   :bundle-root     "BUNDLE_ROOT_V1"
   :evidence-content "EVIDENCE_CONTENT_V1"
   :sensitivity-sentinel-decision "SENSITIVITY_SENTINEL_DECISION_V1"
   :state-diff       "STATE_DIFF_V1"
   :protocol-state   "PROTOCOL_STATE_V1"
   :params-manifest  "PARAMS_MANIFEST_V1"
   :evm-projection   "EVM_PROJECTION_V1"
   :invariant-attestation "INVARIANT_ATTESTATION_V1"
   :projection-evidence "PROJECTION_EVIDENCE_V1"
   :checkpoint-evidence "CHECKPOINT_EVIDENCE_V1"
   :run-overview     "RUN_OVERVIEW_V1"
   :benchmark-certification "BENCHMARK_CERTIFICATION_V1"
   :intent-dsl       "INTENT_DSL_V1"
   :intent-registry-entry "INTENT_REGISTRY_ENTRY_V1"
   :intent-registry "INTENT_REGISTRY_V1"
   :projection-definition "PROJECTION_DEFINITION_V1"
   :projection-definition-registry "PROJECTION_DEFINITION_REGISTRY_V1"
   :projection-artifact "PROJECTION_ARTIFACT_V1"
   :claim-definition "CLAIM_DEFINITION"
   :claim-definition-conceptual "CONCEPT_CLAIM_DEFINITION_V1"
   :attestor         "ATTESTOR"
   :evidence-node    "EVIDENCE_NODE_V1"
   :decision-evidence "DECISION_EVIDENCE_V1"
   :invariant-failure "INVARIANT_FAILURE_V1"
   :startup-validation "STARTUP_VALIDATION_V1"
   :claim-result       "CLAIM_RESULT_V1"
   :attestation        "ATTESTATION_V1"
   :scenario           "SCENARIO_V1"
   :attestation-record "ATTESTATION_RECORD_V1"
   :execution-definition "EXECUTION_DEFINITION_V1"
   :action             "ACTION_V1"
   :action-at          "ACTION_AT_V1"
   :pro-rata-allocation-result "PRO_RATA_ALLOCATION_RESULT_V1"
   :pro-rata-proposed-effects "PRO_RATA_PROPOSED_EFFECTS_V1"
   :pro-rata-effect-refinement "PRO_RATA_EFFECT_REFINEMENT_V1"
   :authorized-effect-execution "AUTHORIZED_EFFECT_EXECUTION_V1"
   :applied-effect-receipt "APPLIED_EFFECT_RECEIPT_V1"
   :applied-adjustment-refinement "APPLIED_ADJUSTMENT_REFINEMENT_V1"
   :stability-snapshot "STABILITY_SNAPSHOT_V1"
   :benchmark-semantic-content "BENCHMARK_SEMANTIC_CONTENT_V1"
   :benchmark-registry-entry "BENCHMARK_REGISTRY_ENTRY_V1"
   :benchmark-outcome "BENCHMARK_OUTCOME_V1"
   :review-round-identity "REVIEW_ROUND_IDENTITY_V1"
   :researcher-run-report "RESEARCHER_RUN_REPORT_V1"
   :researcher-position "RESEARCHER_POSITION_V1"
   :three-member-certificate "THREE_MEMBER_CERTIFICATE_V1"
   :evidence-collection "EVIDENCE_COLLECTION_V1"
   :state-projection "STATE_PROJECTION_V1"
   :research-framework-change-proposal "RESEARCH_FRAMEWORK_CHANGE_PROPOSAL_V1"
   :changelog-challenge "CHANGELOG_CHALLENGE_V1"
   :research-benchmark-model "RESEARCH_BENCHMARK_MODEL_V1"
   :research-theorem-outcome "RESEARCH_THEOREM_OUTCOME_V1"
   :research-conclusion "RESEARCH_CONCLUSION_V1"
   :research-command "RESEARCH_COMMAND_V1"
   :research-command-trace-v2 "RESEARCH_COMMAND_TRACE_V2"
   :dimension-support-v1 "DIMENSION_SUPPORT_V1"
   :research-assignment "RESEARCH_ASSIGNMENT_V1"
   :incentive-model "INCENTIVE_MODEL_V1"
   :incentive-deviation-domain "INCENTIVE_DEVIATION_DOMAIN_V1"
   :research-analysis-closure "RESEARCH_ANALYSIS_CLOSURE_V1"
   :creation-provenance "CREATION_PROVENANCE_V1"
   :trust-sequence-definition "TRUST_SEQUENCE_DEFINITION_V1"
   :procedure-execution-witness "PROCEDURE_EXECUTION_WITNESS_V1"
   :research-force-authorisation "RESEARCH_FORCE_AUTHORISATION_V1"
   :researcher-decision "RESEARCHER_DECISION_V1"
   :researcher-decision-v2 "RESEARCHER_DECISION_V2"
   :researcher-decision-scope "RESEARCHER_DECISION_SCOPE_V1"
   :decision-subject "DECISION_SUBJECT_V1"
   :cancellation-binding "CANCELLATION_BINDING_V1"
   :cancellation-operation "CANCELLATION_OPERATION_V1"
   :cancellation-attempt "CANCELLATION_ATTEMPT_V1"
   :cancellation-execution "CANCELLATION_EXECUTION_V1"
   :cancellation-evaluation-inputs "CANCELLATION_EVALUATION_INPUTS_V1"
   :cancellation-derivation "CANCELLATION_DERIVATION_V1"
   :three-member-authority-report "THREE_MEMBER_AUTHORITY_REPORT_V1"
   :canonical-value-sequence "CANONICAL_VALUE_SEQUENCE_V1"
   :sew-action-root "SEW_ACTION_ROOT_V1"
   :allocation-assurance-certificate "ALLOCATION_ASSURANCE_CERTIFICATE_V1"
   :force-authorisation-reservation "FORCE_AUTHORISATION_RESERVATION_V1"
   :force-authorisation-consumption "FORCE_AUTHORISATION_CONSUMPTION_V1"
   :force-authorisation-consumption-v2 "FORCE_AUTHORISATION_CONSUMPTION_V2"
   :claim-consumption-receipt "CLAIM_CONSUMPTION_RECEIPT_V1"
   :force-authorised-execution-evidence "FORCE_AUTHORISED_EXECUTION_EVIDENCE_V1"
   :force-authorised-execution-evidence-v2 "FORCE_AUTHORISED_EXECUTION_EVIDENCE_V2"
   :authorised-effect-correlation "AUTHORISED_EFFECT_CORRELATION_V1"
   :sew-terminal-state-snapshot "SEW_TERMINAL_STATE_SNAPSHOT_V1"
   :staged-event-evidence "STAGED_EVENT_EVIDENCE_V1"
   :terminal-evidence-publication "TERMINAL_EVIDENCE_PUBLICATION_V1"
   :consensus-terminal-reservation "CONSENSUS_TERMINAL_RESERVATION_V1"
   :generated-case-set "GENERATED_CASE_SET_V1"
   :pro-rata-allocation-evidence "PRO_RATA_ALLOCATION_EVIDENCE_V1"
   :pro-rata-application-evidence "PRO_RATA_APPLICATION_EVIDENCE_V1"
   :pro-rata-execution-evidence "PRO_RATA_EXECUTION_EVIDENCE_V1"
   :pro-rata-execution-evidence-v2 "PRO_RATA_EXECUTION_EVIDENCE_V2"
   :slash-distribution-policy-v1  "SLASH_DISTRIBUTION_POLICY_V1"
   :slash-distribution-v1         "SLASH_DISTRIBUTION_V1"
   :slash-distribution-application-receipt-v1 "SLASH_DISTRIBUTION_APPLICATION_RECEIPT_V1"
   :fixed-regression-case-v1      "FIXED_REGRESSION_CASE_V1"
   :slash-distribution-application-plan-v1 "SLASH_DISTRIBUTION_APPLICATION_PLAN_V1"
   :slash-distribution-application-plan-v2 "SLASH_DISTRIBUTION_APPLICATION_PLAN_V2"
   :prf-effect-contract-v1 "PRF_EFFECT_CONTRACT_V1"
   :bounty-payable-v1              "BOUNTY_PAYABLE_V1"
   :bounty-payable-backing-v1      "BOUNTY_PAYABLE_BACKING_V1"
   :review-member-canonical-indices "REVIEW_MEMBER_CANONICAL_INDICES_V1"
   :review-member-canonical-indices-entries "REVIEW_MEMBER_CANONICAL_INDICES_ENTRIES_V1"
   :pool-availability-v2   "POOL_AVAILABILITY_V2"
   :pool-reservation       "POOL_RESERVATION_V1"
   :award-calculation-v2   "AWARD_CALCULATION_V2"
   :claim-set              "CLAIM_SET_V1"
   :check-set              "CHECK_SET_V1"
   :award-policy           "AWARD_POLICY_V1"
   :priority-order-v1      "PRIORITY_ORDER_V1"
   :overflow-capability    "OVERFLOW_CAPABILITY_V1"
   :overflow-authorisation-scope "OVERFLOW_AUTHORISATION_SCOPE_V1"
   :overflow-capacity-context "OVERFLOW_CAPACITY_CONTEXT_V1"
   :with-bounty-policy-v1     "WITH_BOUNTY_POLICY_V1"
   :with-bounty-invocation-v1 "WITH_BOUNTY_INVOCATION_V1"
   :with-bounty-obligation-v1 "WITH_BOUNTY_OBLIGATION_V1"
   :with-bounty-effect-v1     "WITH_BOUNTY_EFFECT_V1"
   :with-bounty-effect-set-v1 "WITH_BOUNTY_EFFECT_SET_V1"
   :with-bounty-application-plan-v1 "WITH_BOUNTY_APPLICATION_PLAN_V1"
   :with-bounty-transition-evidence-v1 "WITH_BOUNTY_TRANSITION_EVIDENCE_V1"
   :with-bounty-verification-basis-v1 "WITH_BOUNTY_VERIFICATION_BASIS_V1"
   :with-bounty-public-result-v1 "WITH_BOUNTY_PUBLIC_RESULT_V1"
   :allocation-context         "ALLOCATION_CONTEXT_V1"
   :claimant-set               "CLAIMANT_SET_V1"
   :outcome-set                "OUTCOME_SET_V1"
   :proposed-rates             "PROPOSED_RATES_V1"
   :rate-derived-summary       "RATE_DERIVED_SUMMARY_V1"
   :selected-outcome           "SELECTED_OUTCOME_V1"
   :result-root                "RESULT_ROOT_V1"
   :certificate-assertions     "CERTIFICATE_ASSERTIONS_V1"
   :certificate-assertions-v2  "CERTIFICATE_ASSERTIONS_V2"
   :lab-parameter-root      "LAB_PARAMETER_ROOT_V1"
   :lab-withdrawal-fcfs     "LAB_WITHDRAWAL_FCFS_V1"
   :fail-action-policy      "FAIL_ACTION_POLICY_V1"
   :realized-allocation-statement "REALIZED_ALLOCATION_STATEMENT_V1"
   :realized-request-set    "REALIZED_REQUEST_SET_V1"
   :allocation-policy       "ALLOCATION_POLICY_V1"
   :realized-results        "REALIZED_RESULTS_V1"
   :round-lifecycle         "ROUND_LIFECYCLE_V1"
   :scenario-evidence-binding "SCENARIO_EVIDENCE_BINDING_V1"
   :allocation-activation   "ALLOCATION_ACTIVATION_V1"
   :allocation-activation-policy "ALLOCATION_ACTIVATION_POLICY_V1"
   :confidence-composition-v1 "CONFIDENCE_COMPOSITION_V1"
   :research-command-trace-v1 "RESEARCH_COMMAND_TRACE_V1"
   :extension-envelope-shape-v1 "EXTENSION_ENVELOPE_SHAPE_V1"
   :extension-lockfile-v1      "EXTENSION_LOCKFILE_V1"
   :extension-resolution-v1    "EXTENSION_RESOLUTION_V1"
   :extension-capability-descriptor-v1 "EXTENSION_CAPABILITY_DESCRIPTOR_V1"
   :extension-package-manifest-v1     "EXTENSION_PACKAGE_MANIFEST_V1"
   :benchmark-conservation-v1         "BENCHMARK_CONSERVATION_V1"
   :benchmark-input-set-v1            "BENCHMARK_INPUT_SET_V1"
   :benchmark-content-registry-v1     "BENCHMARK_CONTENT_REGISTRY_V1"
   :benchmark-finalization-v1         "BENCHMARK_FINALIZATION_V1"
   :suite-definition-v1               "SUITE_DEFINITION_V1"
   :native-exact-replication-v1       "NATIVE_EXACT_REPLICATION_V1"
   :conformance-reproduction-lineage-v1 "conformance.reproduction-lineage.v1"
   :conformance-validator-implementation-v1 "conformance.validator-implementation.v1"
   :evidence-package-admission-v1     "evidence-package-admission.v1"
   :benchmark-execution-descriptor-v1 "BENCHMARK_EXECUTION_DESCRIPTOR_V1"
   :benchmark-execution-parameters-v1 "BENCHMARK_EXECUTION_PARAMETERS_V1"
   :benchmark-execution-protocol-config-v1 "BENCHMARK_EXECUTION_PROTOCOL_CONFIG_V1"
   :claim-outcome-v1                  "CLAIM_OUTCOME_V1"
   :community-attestation-v0          "COMMUNITY_ATTESTATION_V0"
   :community-code-v0                 "COMMUNITY_CODE_V0"
   :community-env-v0                  "COMMUNITY_ENV_V0"
   :community-finding-v0              "COMMUNITY_FINDING_V0"
   :community-mailbox-v0              "COMMUNITY_MAILBOX_V0"
   :community-stable-result-v0        "COMMUNITY_STABLE_RESULT_V0"
   :community-task-v0                 "COMMUNITY_TASK_V0"
   :comparability-shared-v1           "COMPARABILITY_SHARED_V1"
   :composition-combination-v1        "COMPOSITION_COMBINATION_V1"
   :composition-contract-v1           "COMPOSITION_CONTRACT_V1"
   :composition-plan-v1               "COMPOSITION_PLAN_V1"
   :default-build-attestation-v1      "DEFAULT_BUILD_ATTESTATION_V1"
   :default-build-smoke-output-v1     "DEFAULT_BUILD_SMOKE_OUTPUT_V1"
   :deferral-v1                       "DEFERRAL_V1"
   :evidence-graph-v1                 "EVIDENCE_GRAPH_V1"
   :held-adjustment-v1                "HELD_ADJUSTMENT_V1"
   :prf-artifact-publish-decision-v1  "PRF_ARTIFACT_PUBLISH_DECISION_V1"
   :prf-artifact-publish-manifest-v1  "PRF_ARTIFACT_PUBLISH_MANIFEST_V1"
   :prf-artifact-publish-request-v1   "PRF_ARTIFACT_PUBLISH_REQUEST_V1"
   :prf-authorisation-instance-v1     "PRF_AUTHORISATION_INSTANCE_V1"
   :prf-authorisation-provenance-v1   "PRF_AUTHORISATION_PROVENANCE_V1"
   :prf-contract-schema-v1            "PRF_CONTRACT_SCHEMA_V1"
   :prf-force-authorisation-policy-v1 "PRF_FORCE_AUTHORISATION_POLICY_V1"
   :prf-release-attestation-payload-v1 "PRF_RELEASE_ATTESTATION_PAYLOAD_V1"
   :prf-resubmission-issue-request-v1 "PRF_RESUBMISSION_ISSUE_REQUEST_V1"
   :prf-sensitivity-sentinel-decision-v1 "PRF_SENSITIVITY_SENTINEL_DECISION_V1"
   :prf-sensitivity-sentinel-projection-v1 "PRF_SENSITIVITY_SENTINEL_PROJECTION_V1"
   :prf-sensitivity-sentinel-request-v1 "PRF_SENSITIVITY_SENTINEL_REQUEST_V1"
   :prf-verdict-policy-v1             "PRF_VERDICT_POLICY_V1"
   :pro-rata-evaluation-v1            "PRO_RATA_EVALUATION_V1"
   :scenario-distribution-v1          "SCENARIO_DISTRIBUTION_V1"
   :var-projection-v1                 "VAR_PROJECTION_V1"
   :workflow-group-v1                 "WORKFLOW_GROUP_V1"
   :workflow-group-member-v1          "WORKFLOW_GROUP_MEMBER_V1"
   :conformance-bundle-v1             "conformance.bundle.v1"
   :conformance-derivation-chain-v1   "conformance.derivation-chain.v1"
   :conformance-profile-v1            "conformance.profile.v1"
   :conformance-signature-verification-v1 "conformance.signature-verification.v1"
   :conformance-subject-set-v1        "conformance.subject-set.v1"
   :conformance-validation-subject-v1 "conformance.validation-subject.v1"
   :force-authorisation-scope         "force-authorisation-scope"
   :prf-attempt-disposition-v1        "prf.attempt-disposition.v1"
   :prf-researcher-resubmission-v1    "prf.researcher-resubmission.v1"
   :prf-resubmission-chain-state-v1   "prf.resubmission-chain-state.v1"
   :prf-resubmission-family-v1        "prf.resubmission-family.v1"
   :prf-resubmission-idempotency-v1   "prf.resubmission-idempotency.v1"
   :prf-submission-attempt-receipt-v1 "prf.submission-attempt-receipt.v1"
   :prf-submission-basis-v1           "prf.submission-basis.v1"
   :prf-submission-bundle-v1          "prf.submission-bundle.v1"
   :prf-transaction-effects-v1        "prf.transaction-effects.v1"
   :prf-transaction-input-v1         "prf.transaction-input.v1"
   :prf-transaction-ordering-change-identity-v1 "prf.transaction-ordering-change-identity.v1"
   :prf-transaction-ordering-v1       "prf.transaction-ordering.v1"
   :related-claims-member             "related-claims-member"
   :withdrawal-ledger-v1              "withdrawal-ledger.v1"
   :prf-protocol-genesis-v1           "PRF_PROTOCOL_GENESIS_V1"
   :prf-chain-instance-genesis-v1     "PRF_CHAIN_INSTANCE_GENESIS_V1"
   :prf-chain-configuration-v1        "PRF_CHAIN_CONFIGURATION_V1"
   :prf-chain-configuration-transition-v1 "PRF_CHAIN_CONFIGURATION_TRANSITION_V1"})

;; ──────────────────────────────────────────────────────────────────────────────
;; varuint Encoding (LEB128, little-endian base-128)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- encode-varuint
  "Encode a non-negative integer as LEB128 varuint.
   Minimal representation: no leading zeros."
  [n]
  (let [bos (ByteArrayOutputStream.)
        n (biginteger n)]
    (loop [n n]
      (let [b (.byteValue (.and n (BigInteger/valueOf 0x7F)))
            n' (.shiftRight n 7)]
        (if (.equals n' BigInteger/ZERO)
          (do (.write bos (int b))
              (.toByteArray bos))
          (do (.write bos (int (bit-or (int b) 0x80)))
              (recur n')))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; ZigZag Encoding (signed → unsigned for varuint)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- zigzag
  "ZigZag encode a signed integer of arbitrary precision to an unsigned integer.
   n >= 0 → 2n
   n <  0 → -2n - 1"
  [n]
  (if (neg? n)
    (-' (*' -2 n) 1)
    (*' 2 n)))

;; ──────────────────────────────────────────────────────────────────────────────
;; UTF-8 Encoding
;; ──────────────────────────────────────────────────────────────────────────────

(defn- utf8-bytes
  "Encode a string as UTF-8 byte array."
  [s]
  (.getBytes s "UTF-8"))

;; ──────────────────────────────────────────────────────────────────────────────
;; Keyword Name
;; ──────────────────────────────────────────────────────────────────────────────

(defn- keyword-string
  "Return the portable string representation of a keyword.
   :resolver/id → \"resolver/id\"
   :active      → \"active\""
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Integer coercion
;; ──────────────────────────────────────────────────────────────────────────────

(defn- coerce-integer
  "Coerce any Clojure/Java integer type to BigInteger for uniform handling."
  [v]
  (cond
    (instance? BigInteger v) v
    (instance? Long v) (BigInteger/valueOf (long v))
    (instance? Integer v) (BigInteger/valueOf (int v))
    (instance? clojure.lang.BigInt v) (.toBigInteger v)
    (instance? Short v) (BigInteger/valueOf (short v))
    (instance? Byte v) (BigInteger/valueOf (byte v))
    :else (BigInteger/valueOf (long v))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Byte array helpers
;; ──────────────────────────────────────────────────────────────────────────────

(defn- ba-concat
  "Concatenate multiple byte-arrays into one."
  [& bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0, bas bas]
      (when (seq bas)
        (let [ba (first bas)]
          (System/arraycopy ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (rest bas)))))
    out))

(defn- ba-concat-all
  "Concatenate a seq of byte-arrays into one byte-array in a single pass.
   Unlike (apply ba-concat bas), this does not realize the full argument
   sequence into an Object[] for the variadic apply, so very large flat
   collections (many array/map elements) do not pay the arg-list materialization
   cost.  Single allocation; each input is copied exactly once."
  [bas]
  (let [bas (seq bas)
        total (reduce + 0 (map count bas))
        out (byte-array total)]
    (loop [idx 0, bas bas]
      (when bas
        (let [ba (first bas)]
          (System/arraycopy ^bytes ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (next bas)))))
    out))

(defn- ba-of
  "Create a byte-array from individual byte arguments."
  [& bs]
  (byte-array bs))

(defn- byte-compare
  "Lexicographic byte comparison. Returns a negative number if a < b,
   zero if a == b, and a positive number if a > b."
  [^bytes a ^bytes b]
  (let [alen (count a)
        blen (count b)
        minlen (min alen blen)]
    (loop [i 0]
      (if (= i minlen)
        (- alen blen)
        (let [ai (bit-and (int (aget a i)) 0xFF)
              bi (bit-and (int (aget b i)) 0xFF)]
          (if (= ai bi)
            (recur (inc i))
            (- ai bi)))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Validation
;; ──────────────────────────────────────────────────────────────────────────────

(defn- canonical-type?
  "Return true if v is a supported canonical type."
  [v]
  (or (nil? v)
      (instance? Boolean v)
      (instance? Long v)
      (instance? Integer v)
      (instance? Short v)
      (instance? Byte v)
      (instance? clojure.lang.BigInt v)
      (instance? BigInteger v)
      (instance? String v)
      (instance? clojure.lang.Keyword v)
      (instance? clojure.lang.IPersistentVector v)
      (instance? clojure.lang.IPersistentMap v)))

(defn- map-key-type?
  "Return true if k is a permitted map key type."
  [k]
  (or (instance? String k)
      (instance? clojure.lang.Keyword k)))

(defn- out-of-domain!
  "Fail closed: the value is outside the canonical type algebra
   (CANONICAL_HASH_SPEC_V1 §4/§5).  It must be projected to a canonical-safe
   representation before encoding — the encoder and validators never perform
   silent coercion.  Always throws; never returns.  When a path is supplied it
   is reported so recursive rejections name the exact nested location."
  [x host-type guidance & [path]]
  (throw (ex-info "Value is outside the canonical type domain; project it to a canonical-safe representation before hashing"
                  {:type :canonical/out-of-domain
                   :host-type host-type
                   :value-class (some-> x class .getName)
                   :guidance guidance
                   :path (vec path)})))

(defn validate-canonical-value!
  "Walk a value tree and validate that all values are supported
    canonical types. Throws ex-info on the first unsupported type with the
    exact nested :path of the offending value.  Map keys must be String or
    Keyword.

    STRICT DOMAIN: matches the encoder.  Records, map entries, sets, seqs,
    temporal values, and every other host type outside the canonical type
    algebra are rejected with a structured :canonical/out-of-domain error."
  [v]
  (let [walker (fn walk [x path]
                 (cond
                   (nil? x) x
                   (instance? Boolean x) x
                   (instance? Long x) x
                   (instance? Integer x) x
                   (instance? Short x) x
                   (instance? Byte x) x
                   (instance? clojure.lang.BigInt x) x
                   (instance? BigInteger x) x
                   (instance? String x) x
                   (instance? clojure.lang.Keyword x) x
                   (instance? clojure.lang.IMapEntry x)
                   (out-of-domain! x "clojure.lang.IMapEntry"
                                   "map entries are not canonical values; project to an explicit vector before hashing"
                                   (conj path :map-entry))
                   (instance? clojure.lang.IRecord x)
                   (out-of-domain! x "clojure.lang.IRecord"
                                   "records are prohibited (spec §5); convert to a plain map with (into {} x) before hashing"
                                   (conj path :record))
                   (instance? clojure.lang.IPersistentVector x)
                   (do (run! (fn [e] (walk e (conj path :vector))) x) x)
                   (instance? clojure.lang.IPersistentMap x)
                   (do (doseq [[k v] x]
                         (when-not (map-key-type? k)
                           (throw (ex-info "Map key must be String or Keyword"
                                           {:key k :type (type k) :path (conj path :key)})))
                         (walk v (conj path k)))
                       x)
                   :else
                   (out-of-domain! x (some-> x class .getName)
                                   "value is outside the canonical type algebra; project it to a canonical-safe representation before hashing"
                                   (conj path :value))))]
    (walker v [])
    nil))

;; ──────────────────────────────────────────────────────────────────────────────
;; Canonical Bytes
;; ──────────────────────────────────────────────────────────────────────────────

(defn canonical-bytes
  "Produce the canonical typed binary encoding of a value.
    Returns a byte-array per CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.

    STRICT DOMAIN: this is the normative encoder.  Only the canonical type
    algebra (CANONICAL_HASH_SPEC_V1 §3) may reach it — nil, boolean, integer,
    string, keyword, vector, map.  Every host type outside that algebra — sets,
    seqs/lists/lazy-seqs, map entries, records, temporal values, ratios,
    floats, BigDecimal, Java collections/arrays — is REJECTED with a structured
    :canonical/out-of-domain error rather than silently coerced into an
    in-domain representation.  Callers that genuinely want a set→sorted-vector
    or list→vector conversion must perform it explicitly (see
    project-world-to-structure-view) before entering this boundary.

    Implemented iteratively over an explicit work stack so that deeply
    nested structures (per spec §8.4 concatenation stress) do not overflow
    the JVM stack. Output is byte-identical to a direct recursive walk."
  [v]
  (loop [work [[:encode v]]
         done []]
    (if (empty? work)
      (peek done)
      (let [task (peek work)
            work (pop work)]
        (case (nth task 0)
          :encode
          (let [x (nth task 1)]
            (cond
              (nil? x)
              (recur work (conj done (ba-of tag-null)))

              (instance? Boolean x)
              (recur work (conj done (ba-of (if x tag-bool-true tag-bool-false))))

              ;; Only integer types have a canonical representation.  Do not use
              ;; number? here: coercing floating-point values or ratios to long
              ;; silently aliases distinct semantic values (for example, 1 and 1.9).
              ;; Integer widths (Long/Integer/Short/Byte/BigInt/BigInteger) are
              ;; declared equivalent — they are numerically equal canonical
              ;; integers and encode identically.
              (or (instance? Long x)
                  (instance? Integer x)
                  (instance? Short x)
                  (instance? Byte x)
                  (instance? clojure.lang.BigInt x)
                  (instance? BigInteger x))
              (let [bi (coerce-integer x)
                    vu (encode-varuint (zigzag bi))]
                (recur work (conj done (ba-concat (ba-of tag-int) vu))))

              (instance? String x)
              (let [bs (utf8-bytes x)
                    len (encode-varuint (count bs))]
                (recur work (conj done (ba-concat (ba-of tag-string) len bs))))

              (instance? clojure.lang.Keyword x)
              (let [s (keyword-string x)
                    bs (utf8-bytes s)
                    len (encode-varuint (count bs))]
                (recur work (conj done (ba-concat (ba-of tag-keyword) len bs))))

              ;; ── out-of-domain host types (rejected, never coerced) ──────────

              (instance? java.time.temporal.TemporalAccessor x)
              (out-of-domain! x "java.time.temporal.TemporalAccessor"
                              "temporal values are outside the canonical domain; project to an ISO-8601 string before hashing")

              (instance? java.math.BigDecimal x)
              (out-of-domain! x "java.math.BigDecimal"
                              "BigDecimal is outside the canonical domain; project to a canonical-safe representation before hashing")

              (instance? clojure.lang.IMapEntry x)
              (out-of-domain! x "clojure.lang.IMapEntry"
                              "map entries are not canonical values; project to an explicit vector before hashing")

              (instance? clojure.lang.IRecord x)
              (out-of-domain! x "clojure.lang.IRecord"
                              "records are prohibited (spec §5); convert to a plain map with (into {} x) before hashing")

              (instance? clojure.lang.ISeq x)
              (out-of-domain! x "clojure.lang.ISeq"
                              "seqs/lists/lazy-seqs are outside the canonical domain; realize to a vector before hashing")

              (instance? clojure.lang.IPersistentSet x)
              (out-of-domain! x "clojure.lang.IPersistentSet"
                              "sets are outside the canonical domain; project to a sorted vector before hashing")

              ;; ── canonical composite types ───────────────────────────────────

              (instance? clojure.lang.IPersistentVector x)
              (let [n (count x)]
                (recur (into (conj work [:array n])
                             (map (fn [e] [:encode e]) (reverse x)))
                       done))

              (instance? clojure.lang.IPersistentMap x)
              (let [entries (into [] x)
                    n (count entries)
                    key-encodes (map (fn [e] [:encode (first e)]) (reverse entries))]
                (recur (into (conj work [:map-keys n entries]) key-encodes)
                       done))

              :else
              (out-of-domain! x (some-> x class .getName)
                              "value is outside the canonical type algebra; project it to a canonical-safe representation before hashing")))

          :array
          (let [n (nth task 1)
                split (- (count done) n)
                elems (subvec done split)
                rest-done (subvec done 0 split)
                combined (ba-concat-all (list* (ba-of tag-array) (encode-varuint n) elems))]
            (recur work (conj rest-done combined)))

          :map-keys
          (let [n (nth task 1)
                entries (nth task 2)
                split (- (count done) n)
                key-bytes (subvec done split)
                rest-done (subvec done 0 split)
                pairs (mapv (fn [e kb] {:key-bytes kb :val (second e)})
                            entries key-bytes)
                sorted (sort-by :key-bytes
                                (fn [^bytes a ^bytes b]
                                  (neg? (byte-compare a b)))
                                pairs)
                sorted-keys (mapv :key-bytes sorted)
                val-encodes (map (fn [v] [:encode v])
                                 (reverse (mapv :val sorted)))]
            (recur (into (conj work [:map-combine n sorted-keys]) val-encodes)
                   rest-done))

          :map-combine
          (let [n (nth task 1)
                key-bytes (nth task 2)
                split (- (count done) n)
                val-bytes (subvec done split)
                rest-done (subvec done 0 split)
                elements (mapcat (fn [kb vb] [kb vb]) key-bytes val-bytes)
                combined (ba-concat-all (list* (ba-of tag-map) (encode-varuint n) elements))]
            (recur work (conj rest-done combined))))))))

(defn canonical-bytes-hex
  "Lowercase hex of a value's canonical encoding (deterministic sort key and
   portable canonical-bytes commitment)."
  [v]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (canonical-bytes v))))

(defn canonical-commitment
  "Build the portable canonical commitment fields for a content-addressed body
   that is hashed as sha256(domain-tag || canonical-bytes(body)):

     {:canonical/bytes <hex of canonical-bytes(body)>
      :canonical/hash <sha256:<hex> domain-separated hash>}

   A cross-language verifier recomputes
   sha256(domain-tag || hex-decode(:canonical/bytes)) == :canonical/hash and
   then checks that against the committed artifact hash — no Clojure-specific
   preimage (pr-str) is required."
  [domain-tag body]
  {:canonical/bytes (canonical-bytes-hex body)
   :canonical/hash (hash-ref/sha256-ref (domain-hash domain-tag body))})

(defn canonical-commitment-valid?
  "Verify a canonical commitment against the body it claims to commit: the
   committed :canonical/bytes must be canonical-bytes(body) and the committed
   :canonical/hash must equal sha256(domain-tag || canonical-bytes(body)).
   A commitment with neither field (legacy artifact) validates as true."
  [domain-tag body commitment]
  (let [cb (:canonical/bytes commitment)
        ch (:canonical/hash commitment)]
    (or (and (nil? cb) (nil? ch))
        (and (string? cb)
             (string? ch)
             (= cb (canonical-bytes-hex body))
             (= ch (hash-ref/sha256-ref (domain-hash domain-tag body)))))))

(defn project-committable-content
  "Project a value into canonical-safe form for content-addressing, byte-identical
   to the strict encoder's former coercions for representable runtime types:

     ratio             → {:canonical/type \"ratio\"
                           :canonical/numerator n :canonical/denominator d}
     double            → {:canonical/type \"float64\" :canonical/hex (exact)}
     float             → {:canonical/type \"float32\" :canonical/hex (exact)}
     BigDecimal        → {:canonical/type \"big-decimal\"
                           :canonical/value (.toPlainString x)}
     temporal value    → ISO-8601 string
     set               → sorted vector (canonical byte order)
     seq / map entry   → vector
     map / vector      → recursed

   All canonical values pass through unchanged, so existing commitments are
   unchanged; content that previously could not be hashed at all (ratios,
   doubles, floats, BigDecimal) becomes content-addressable.  Records remain
   rejected — convert them to plain maps explicitly."
  [v]
  (letfn [(walk [x]
            (cond
              (instance? clojure.lang.Ratio x)
              {:canonical/type "ratio"
               :canonical/numerator (numerator x)
               :canonical/denominator (denominator x)}
              (instance? Double x)
              {:canonical/type "float64" :canonical/hex (Double/toHexString x)}
              (instance? Float x)
              {:canonical/type "float32" :canonical/hex (Float/toHexString x)}
              (instance? java.math.BigDecimal x)
              {:canonical/type "big-decimal" :canonical/value (.toPlainString x)}
              (instance? java.time.temporal.TemporalAccessor x)
              (str x)
              (set? x) (vec (sort-by canonical-bytes-hex (map walk x)))
              (map? x) (into {} (map (fn [[k val]] [(walk k) (walk val)]) x))
              (vector? x) (mapv walk x)
              (sequential? x) (mapv walk x)
              :else x))]
    (walk v)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Projection Helpers
;; ──────────────────────────────────────────────────────────────────────────────

(defn project-identity
  "Identity projection: pass through unchanged.
   Accepts optional intent arg (ignored) for hash-with-intent
   compatibility with the projection-fn calling convention."
  [x & _]
  x)

(defn project-self-hash-stripped
  "Cancel root-level self-hash keys (see self-hash-keys) and pass everything
   else through unchanged.  Used by intents that declare a self-hash exclusion
   but whose payload carries no other projection need.  Cancellation is
   root-level only; nested self-hash keys are ordinary content."
  [value _intent]
  (strip-self-hash-fields value))

(defn project-canonical-safe
  "Deep-project a value into canonical-safe form for canonical encoding: sets →
   sorted vectors (by string representation), maps and sequential values
   recursed, all other values passed through unchanged.

   This is the identity projection on canonical-safe values (nil, boolean,
   integer, string, keyword, vector, map), so existing commitments are
   byte-unchanged; set- and seq-bearing bodies that could not previously be
   hashed become content-addressable.  Map keys are preserved as-is.  Idempotent."
  [v]
  (cond
    (set? v) (vec (sort-by str v))
    (map? v) (into {} (map (fn [[k vv]] [k (project-canonical-safe vv)]) v))
    (sequential? v) (mapv project-canonical-safe v)
    :else v))

;; ──────────────────────────────────────────────────────────────────────────────
;; World State Projection
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Intent-aware semantic projection. This is NOT generic serialization.
;; It extracts identity-relevant structure from a simulation world state
;; for downstream claims, invariants, and projection-based allocation.
;;
;; Projection boundary rules:
;;   PRESERVE (identity-critical):
;;     - integers, strings, keywords, booleans
;;     - maps, vectors (structural identity)
;;   TRANSFORM (canonicalize only):
;;     - sets              → sorted vectors
;;     - java.time.Instant → ISO-8601 string
;;     - Double/Float      → {:type :float64 :value-str "..."}
;;     - Ratio             → {:type :ratio :value-str "..."}
;;   REPLACE (structure-only abstraction):
;;     - functions → {:type :fn}   (structured marker, not bare keyword)
;;
;; The result is a pure canonical value tree with the intent bound
;; explicitly: {:intent <kw> :structure <walked-world>}.

(defn project-world-to-structure-view
  "Project world state into a deterministic, canonical-safe structure view.

   This is a *semantic projection*, not serialization. It selects
   identity-relevant structure for downstream claims, invariants, and
   projection-based allocation systems.

   Validation order:
     runtime world → project-world-to-structure-view → validate-canonical-value!
     → domain-hash with :world-structure

   The projection function normalizes projectable runtime types BEFORE
   canonical validation. Type-category exclusions (:functions, :sets,
   :ratios, :instants, :doubles) are NOT in :intent/excludes because
   they are handled here, not rejected before projection. Only semantic
   exclusions (top-level key names) belong in :intent/excludes.

   Intent-aware: the intent keyword is bound into the output, making
   the projection lens explicit for hash-with-intent and evidence chain
   reproducibility.

   Projection boundary (what is preserved vs transformed vs replaced):
     Preserve — integers, strings, keywords, booleans, maps, vectors
     Transform — sets→sorted-vectors, Instant→ISO-string,
                 Float/Double→{:type :float64 :value-str \"%.17g\"}
                 Ratio→{:type :ratio :value-str \"%.17g\"}
     Replace  — functions→{:type :fn} (structured marker)

   When flattened-fields-atom is provided, records each flattening event
   as {:path [...], :type kw, :value raw, :contract kw} into the atom.
   This metadata is excluded from the returned structure so hashes remain
   stable; callers attach it externally as :projection/flattened-fields.

   Idempotent and fully deterministic across JVM invocations.
   Output passes validate-canonical-value! and is safe for canonical-bytes."
  [world intent & [flattened-fields-atom]]
  (letfn [(walk [x path]
            (cond
              ;; Primitives — pass through unchanged
              (nil? x) nil
              (boolean? x) x
              (integer? x) x
              (string? x) x
              (keyword? x) x
              ;; java.time.Instant → ISO-8601 string
              (instance? java.time.Instant x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :instant
                            :value (.toString x)
                            :contract :instant→iso8601-string}))
                  (.toString x))
              ;; Double, Float → tagged representation (preserves type identity)
              (instance? Double x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :float64
                            :value (double x)
                            :contract :float64-tagged-representation}))
                  {:type :float64 :value-str (format "%.17g" (double x))})
              (instance? Float x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :float64
                            :value (float x)
                            :contract :float64-tagged-representation}))
                  {:type :float64 :value-str (format "%.17g" (float x))})
               ;; Ratio → exact tagged representation. Converting through double
               ;; would alias distinct rational values in a commitment.
              (instance? clojure.lang.Ratio x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :ratio
                            :value (str (numerator x) "/" (denominator x))
                            :contract :ratio-tagged-representation}))
                  {:type :ratio
                   :value-str (format "%.17g" (double x))
                   :numerator (numerator x)
                   :denominator (denominator x)})
              ;; BigDecimal → tagged float64 representation
              (instance? java.math.BigDecimal x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :float64
                            :value (.doubleValue x)
                            :contract :bigdecimal→float64-tagged-representation}))
                  {:type :float64 :value-str (format "%.17g" (.doubleValue x))})
              ;; Function → structured marker (NOT lossy :fn atom)
              (fn? x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :function
                            :value (pr-str x)
                            :contract :function→fn-marker}))
                  {:type :fn})
              ;; Vector — recurse elements
              (vector? x) (mapv #(walk % (conj path [:vector %2])) x (range))
              ;; Map — recurse keys and values; ordering at encode time.
              ;; The strict encoder now encodes map keys as full canonical values,
              ;; so composite keys (structured ids) survive the projection as-is.
              (map? x)
              (persistent!
               (reduce-kv (fn [m k v]
                            (assoc! m
                                    (walk k (conj path [:key k]))
                                    (walk v (conj path [:value k]))))
                          (transient {}) x))
              ;; Set → sorted deterministic vector
              (set? x) (do (when flattened-fields-atom
                             (swap! flattened-fields-atom conj
                                    {:path path
                                     :type :set
                                     :value (vec (sort (map str x)))
                                     :contract :set→sorted-vector}))
                           (vec (sort (map #(walk % (conj path [:set %2])) x (range)))))
              ;; List, LazySeq, etc. → vector for canonical encoding compliance
              (sequential? x)
              (do (when flattened-fields-atom
                    (swap! flattened-fields-atom conj
                           {:path path
                            :type :sequential
                            :value (vec x)
                            :contract :sequential→vector}))
                  (mapv #(walk % (conj path [:sequential %2])) x (range)))
              :else
              (throw (ex-info
                      "Cannot project unsupported type to structure view"
                      {:type (type x) :value x}))))]
    {:intent intent
     :structure (walk world [])
     :projection/flattened-fields (when flattened-fields-atom (vec @flattened-fields-atom))}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Hashing
;; ──────────────────────────────────────────────────────────────────────────────

(defn hash-bytes
  "Compute raw SHA-256 digest of a byte array.
   Returns a 32-byte byte-array for use in Merkle construction
   and domain-separated hashing."
  [^bytes ba]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest ba)
    (.digest digest)))

(defn- bytes->hex
  "Convert a byte array to a lowercase hex string."
  [^bytes ba]
  (let [sb (StringBuilder.)]
    (doseq [b ba]
      (.append sb (format "%02x" (bit-and (int b) 0xFF))))
    (.toString sb)))

(defn domain-hash
  "Compute a domain-separated canonical hash.
   HASH = SHA256(DOMAIN_TAG || CANONICAL_BYTES)
   where DOMAIN_TAG is the UTF-8 encoding of the domain tag string.
   Returns a 64-char hex string.
   domain-tag should be a keyword from domain-tags, or a string."
  ([v]
   (domain-hash :evidence-record v))
  ([domain-tag v]
   (let [tag-str (if (instance? String domain-tag)
                   domain-tag
                   (or (domain-tags domain-tag)
                       (throw (ex-info "Unknown domain tag"
                                       {:domain-tag domain-tag
                                        :known (keys domain-tags)}))))
         tag-bytes (utf8-bytes tag-str)
         canon (canonical-bytes v)]
     (bytes->hex (hash-bytes (ba-concat tag-bytes canon))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Evidence Content Projection (for JSON-round-trippable hashes)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- project-for-content-hash
  "Project an evidence record into a form suitable for content-addressed
   hashing that survives JSON serialization/deserialization.
   Keywords are converted to strings (matching JSON behavior) and
   maps are sorted for deterministic ordering.
   Accepts optional intent arg (ignored) and optional flattened-fields-atom."
  [data & args]
  (let [[_ flattened-fields-atom] args
        _intent (first args)]
    (letfn [(walk [v path]
              (cond
                (instance? clojure.lang.Keyword v)
                (do (when flattened-fields-atom
                      (swap! flattened-fields-atom conj
                             {:path path
                              :type :keyword
                              :value (keyword-string v)
                              :contract :keyword→string}))
                    (keyword-string v))
                (ratio? v)
                (do (when flattened-fields-atom
                      (swap! flattened-fields-atom conj
                             {:path path
                              :type :ratio
                              :value (str (numerator v) "/" (denominator v))
                              :contract :ratio→tagged-map}))
                    {"$type" "ratio"
                     "$numerator" (numerator v)
                     "$denominator" (denominator v)})
                (instance? Double v)
                (do (when flattened-fields-atom
                      (swap! flattened-fields-atom conj
                             {:path path
                              :type :float64
                              :value (double v)
                              :contract :double→tagged-hex}))
                    {"$type" "float64"
                     "$hex" (Double/toHexString v)})
                (instance? Float v)
                (do (when flattened-fields-atom
                      (swap! flattened-fields-atom conj
                             {:path path
                              :type :float32
                              :value (float v)
                              :contract :float→tagged-hex}))
                    {"$type" "float32"
                     "$hex" (Float/toHexString v)})
                (instance? clojure.lang.IPersistentMap v)
                (into (sorted-map) (map (fn [[k v]] [(walk k (conj path [:key k])) (walk v (conj path [:value k]))]) v))
                (instance? clojure.lang.IPersistentVector v)
                (mapv (fn [x i] (walk x (conj path [:vector i]))) v (range))
                (sequential? v)
                (mapv (fn [x i] (walk x (conj path [:sequential i]))) v (range))
                :else v))]
      (walk data []))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Read string with canonical hashing and flattening provenance
;; ──────────────────────────────────────────────────────────────────────────────

(defn read-string
  "Read an EDN string and produce a canonical hash with flattening provenance.

    Returns {:value parsed-value
             :hash hex-string
             :projection/flattened-fields [<flattening-events>]}.

    The :projection/flattened-fields metadata records every type transformation
    performed during the :evidence-content projection (keyword→string,
    ratio→tagged-map, double→tagged-hex, float→tagged-hex). This enables
    cross-language canonical hashing verification via check-projection-diff.

    Usage:
      (hc/read-string \"{:id 1 :name \\\"test\\\"}\")"
  [s]
  (let [value (edn/read-string s)
        flattened-fields (atom [])
        projected (project-for-content-hash value flattened-fields)
        hash-hex (domain-hash :evidence-content projected)]
    {:value value
     :hash hash-hex
     :projection/flattened-fields (vec @flattened-fields)}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Contract
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Each intent is a machine-readable contract that explicitly declares:
;;   :intent/description — what kind of data this hash represents
;;   :intent/includes    — what data categories are intentionally covered
;;   :intent/excludes    — what data categories are explicitly excluded
;;   :intent/projection-fn — projection function applied before hashing
;;   :intent/domain-tag  — domain tag string for domain-separated hashing
;;   :intent/version     — monotonic integer; projection changes require increment
;;
;; This eliminates semantic drift between intents, provides explicit
;; machine-readable boundaries, and enables future linting support
;; (e.g., check that data being hashed matches declared scope).

(def ^:private self-hash-keys
  "Keys that hold the hash of the artifact currently being hashed.

   These keys are cancelled (stripped) before canonical hashing so an artifact
   does not recursively commit to its own attached hash.  Cancellation is a
   ROOT-LEVEL contract: only a self-hash key at the top level of the hashed
   value is stripped; a nested occurrence is ordinary content and is preserved.

   Do not add reference hashes here. A reference hash commits to another
   artifact or contextual component and is part of the current artifact's
   identity.  In particular, the bare :hash key is NOT listed: :hash is
   context-dependent and must be excluded per-intent when it is a self-hash
   (see :action), per HASH_INTENT_REGISTRY_SPEC_V1 §2.6.

   Examples:
   - self hash:      :node-hash on an evidence node
   - self hash:      :projection-hash on a projection artifact
   - reference hash: :action-hash on an evidence record
   - reference hash: :before-hash / :after-hash on a transition record
   - reference hash: :action-hash inside an :action-at projection
   - reference hash: :prev-hash / :cursor/final-self-hash on an evidence chain

   Per HASH_INTENT_REGISTRY_SPEC_V1 §2.6: only self-hashes are stripped.
   Reference hashes are part of the canonical content because they commit
   the artifact to other artifacts, actions, worlds, claims, attestations,
   registries, or execution contexts."
  #{:canonical-hash
    :intent-hash
    :registry-hash
    :projection-hash
    :allocation-result-hash
    :self-hash
    :node-hash})

(defn- stable-symbol-name
  [x]
  (cond
    (symbol? x) (str x)
    (var? x) (str (.-sym ^clojure.lang.Var x))
    (fn? x) (or (some-> x meta :name str)
                (.getName (class x)))
    :else (str x)))

(defn- project-canonical-artifact-value
  "Project registry/spec artifacts into canonical-safe data.
   This is intentionally generic and additive: it preserves artifact structure
   while converting runtime-only values into stable representations."
  [value]
  (letfn [(walk [x]
            (cond
              (nil? x) nil
              (boolean? x) x
              (integer? x) x
              (string? x) x
              (keyword? x) x
              (symbol? x) {:type :symbol :value (stable-symbol-name x)}
              (instance? java.time.Instant x) (.toString x)
              (instance? Double x) {:type :float64 :value-str (format "%.17g" (double x))}
              (instance? Float x) {:type :float64 :value-str (format "%.17g" (float x))}
              (instance? clojure.lang.Ratio x) {:type :ratio :value-str (format "%.17g" (double x))}
              (fn? x) {:type :fn :name (stable-symbol-name x)}
              (var? x) {:type :var :value (stable-symbol-name x)}
              (vector? x) (mapv walk x)
              (map? x) (persistent!
                        (reduce-kv (fn [m k v]
                                     (assoc! m (walk k) (walk v)))
                                   (transient {})
                                   x))
              (set? x) (vec (sort-by pr-str (map walk x)))
              (sequential? x) (mapv walk x)
              :else (throw (ex-info "Cannot project unsupported artifact value"
                                    {:type (type x) :value x}))))]
    (walk value)))

(defn- strip-self-hash-fields
  [value]
  (if (map? value)
    (apply dissoc value self-hash-keys)
    value))

(defn- project-canonical-artifact
  [value intent]
  {:intent intent
   :artifact (project-canonical-artifact-value (strip-self-hash-fields value))})

(defn project-intent-dsl
  "Canonical projection for INTENT_DSL_SPEC_V1 intent objects."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-intent-registry-entry
  "Canonical projection for a single registered intent entry."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-intent-registry
  "Canonical projection for intent registry artifacts."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-projection-definition
  "Canonical projection for PROJECTION_DEFINITION_REGISTRY_SPEC_V1 entries."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-projection-definition-registry
  "Canonical projection for projection definition registries."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-projection-artifact
  "Canonical projection for PROJECTION_ARTIFACT_SPEC_V1 artifacts."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-pro-rata-allocation-result
  "Canonical projection for PRO_RATA_ALLOCATION_RESULT_V1 artifacts."
  [value intent]
  (project-canonical-artifact value intent))

(defn project-priority-order
  "Canonical projection for PRIORITY_ORDER_V1 artifacts.

   The priority-order body is already canonical-safe; this projection strips
   the self envelope (:artifact/preimage / :artifact/content-hash) and wraps
   the body with the intent so the content hash is domain-separated."
  [value intent]
  (project-canonical-artifact value intent))

(defn- normalize-depends-on
  "Normalize :depends-on to canonical-safe format.
   - Enriched format (maps with :claim-id): extract sorted keyword IDs.
   - Legacy format (keyword vector): pass through as-is.
   - Mixed or empty: handled gracefully."
  [deps]
  (if (and (sequential? deps) (every? map? deps))
    (mapv :claim-id (sort-by :claim-id deps))
    deps))

(defn- enrich-depends-on
  "Normalize :depends-on to enriched format with concept-hash.
   - Enriched format (maps with :claim-id): project claim-id + concept-hash sorted by claim-id.
   - Legacy format (keyword vector): wrap with nil concept-hash, sorted.
   - Returns nil if deps is nil or empty."
  [deps]
  (when (seq deps)
    (if (and (sequential? deps) (every? map? deps))
      (mapv (fn [d] {:claim-id (:claim-id d) :concept-hash (:concept-hash d)})
            (sort-by :claim-id deps))
      (mapv (fn [id] {:claim-id id :concept-hash nil})
            (sort deps)))))

(defn project-claim-definition
  "Canonical projection for CLAIM_DEFINITION_REGISTRY_SPEC_V1 entries.
   Includes only the fields that define claim identity:
     :id, :version, :category, :inputs, :evaluation, :outputs
   Optionally includes :depends-on if present in the source value.
   When :depends-on uses enriched format ({:claim-id <kw> :concept-hash <hex> ...}),
   normalizes to keyword IDs only for backward-compatible structural hashing.
   Excludes :canonical-hash, runtime state, cached values, and generated metadata.
   Non-canonical types (symbols, vars, fns) are projected to canonical-safe
   representations via project-canonical-artifact-value."
  [value intent]
  (let [keep-keys [:id :version :category :inputs :evaluation :outputs]
        artifact (select-keys value keep-keys)
        artifact (if (contains? value :depends-on)
                   (assoc artifact :depends-on (normalize-depends-on (:depends-on value)))
                   artifact)
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-claim-definition-conceptual
  "Canonical projection for CONCEPT_CLAIM_DEFINITION_V1 entries.
   Projects :depends-on with resolved concept-hashes for transitive
   concept-aware hashing. When :depends-on contains enriched maps
   ({:claim-id <kw> :concept-hash <hex>}), projects the full maps sorted
   by claim-id. Falls back to wrapping legacy keyword IDs with nil
   concept-hash for backward compatibility."
  [value intent]
  (let [keep-keys [:id :version :category :inputs :evaluation :outputs]
        artifact (select-keys value keep-keys)
        artifact (if (contains? value :depends-on)
                   (assoc artifact :depends-on (enrich-depends-on (:depends-on value)))
                   artifact)
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-attestor
  "Canonical projection for ATTESTOR_REGISTRY_SPEC_V1 attestor entries.
   Purpose: hash the stable attestor identity and verification surface only.
   Includes exactly:
     :id, :type, :status, :verification, :delegates, :key-history
   Excludes self-hash fields, display/metadata fields, transient runtime state,
   and cached verification data.
   Missing :delegates or :key-history are normalized to empty vectors so the
   projection shape remains explicit and deterministic."
  [value intent]
  (let [artifact {:id (:id value)
                  :type (:type value)
                  :status (:status value)
                  :verification (:verification value)
                  :delegates (vec (or (:delegates value) []))
                  :key-history (vec (or (:key-history value) []))}
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-creation-provenance
  "Canonical projection for a standalone creation-provenance commitment.

   Creation provenance is advisory metadata in the evidence-node projection
   (not in project-evidence-node's select-keys), so it does not affect
   evidence-node identity. This projection exists so provenance can be
   independently root-bound when the caller wants to commit to it."
  [value intent]
  (let [artifact {:creation/provenance (:creation/provenance value)}
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-evidence-node
  "Canonical projection for execution evidence nodes.
   Includes only integrity-relevant execution provenance and evidence hashes.
   Excludes node self-identifiers, timestamps, and policy-filtered visible output
   so metadata-only or presentation-only changes do not alter node identity."
  [value intent]
  (let [artifact {:schema-version (:schema-version value)
                  :parent-hashes (vec (or (:parent-hashes value) []))
                  :bootstrap-roots (vec (or (:bootstrap-roots value) []))
                  :execution (select-keys (:execution value)
                                          [:execution-id :execution-kind :runner
                                           :registry-hash :policy-id :policy-hash])
                  :result {:status (get-in value [:result :status])
                           :summary (get-in value [:result :summary])}
                  :evidence (select-keys (:evidence value)
                                         [:inputs-hash :outputs-hash])
                  :attestations (vec (or (:attestations value) []))
                  :extensions (or (:extensions value) {})}
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-claim-result
  "Canonical projection for CLAIM_RESULT_SPEC_V1 claim evaluation results.
   Includes only the fields that define claim result identity:
     :claim-id, :claim-definition-hash, :holds?, :status
   Excludes :violations (transient diagnostic detail), :evidence-references
   (runtime addressing), and :depends-on (dependency graph)."
  [value intent]
  (let [artifact {:claim-id (:claim-id value)
                  :claim-definition-hash (:claim-definition-hash value)
                  :holds? (boolean (:holds? value))
                  :status (:status value)}
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-attestation
  "Canonical projection for ATTESTATION_SPEC_V1 attestation records.
   Includes only the fields that define attestation identity:
     :attestation-id, :attestor, :subject, :claim, :timestamp
   Excludes :signature (cryptographic proof, not identity) and
   :metadata (ephemeral)."
  [value intent]
  (let [artifact (select-keys value [:attestation-id :attestor :subject :claim :timestamp])
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-attestation-record
  "Canonical projection for ATTESTATION_RECORD_V1 attestation records.
   Includes the fields that define attestation identity:
     schema-version, subject-hash, subject-kind, claim-id, claim-result,
     attestor-id, signing-key-id, signed-at, provenance
   Excludes self-hash/id/signature/metadata (volatile or derived)."
  [value intent]
  (let [keep-keys [:schema-version
                   :attestation/subject-hash :attestation/subject-kind
                   :attestation/claim-id :attestation/claim-result
                   :attestation/attestor-id :attestation/signing-key-id
                   :attestation/signed-at :attestation/provenance]
        artifact (select-keys value keep-keys)
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-execution-definition
  "Canonical projection for EXECUTION_REGISTRY_SPEC_V1 execution definition entries.
   Includes only the fields that define execution identity:
     :id, :version, :kind, :runner, :entry, :execution/type, :execution/mode, :claims
   Excludes :description (documentation) and :depends-on (dependency graph)."
  [value intent]
  (let [keep-keys [:id :version :kind :runner :entry :execution/type :execution/mode :claims]
        artifact (select-keys value keep-keys)
        artifact (project-canonical-artifact-value artifact)]
    {:intent intent
     :artifact artifact}))

(defn project-action
  "Canonical projection for ACTION_V1 action records.
   Accepts a map (with :action/type or :type) or a simple value (string/keyword).
   - map: normalizes :type to :action/type, strips self-hashes
   - simple: wraps as {:action/type value}
   Requires :action/type to be present after normalization."
  [value intent]
  (let [value (cond
                (or (string? value) (keyword? value))
                {:action/type value}
                (and (map? value) (not (:action/type value)) (:type value))
                (-> (assoc value :action/type (:type value))
                    (dissoc :type))
                :else
                value)
        _ (when-not (:action/type value)
            (throw (ex-info "Action must have :action/type"
                            {:value value})))
        artifact (-> value
                     (dissoc :hash)   ; intent-specific: bare :hash is a self-hash on actions (§2.6)
                     strip-self-hash-fields
                     project-canonical-artifact-value)]
    {:intent intent
     :artifact artifact}))

(defn project-action-at
  "Canonical projection for ACTION_AT_V1 action occurrence records.
   Whitelist-only: includes exactly :action-hash, :step, and :block-time.
   Rejects unexpected keys and requires all three fields."
  [value intent]
  (let [allowed #{:action-hash :step :block-time}
        extra (remove allowed (keys value))]
    (when (seq extra)
      (throw (ex-info (str "Unexpected keys in action-at: " (pr-str extra))
                      {:extra extra :value value})))
    (when (or (nil? (:action-hash value))
              (nil? (:step value))
              (nil? (:block-time value)))
      (throw (ex-info "action-at requires :action-hash, :step, and :block-time"
                      {:value value})))
    (let [artifact (project-canonical-artifact-value
                    (select-keys value [:action-hash :step :block-time]))]
      {:intent intent
       :artifact artifact})))

(defn project-stability-snapshot
  "Project a stability snapshot for hash computation.
   Takes {:files {\"path\" \"content\" ...}} and sorts by path
   for deterministic hashing. Each value is canonicalized as a string."
  [value intent]
  (let [files (get value :files {})
        sorted (into (sorted-map) (map (fn [[k v]] [(str k) (str v)]) files))]
    {:intent intent
     :stability/files sorted}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Bounty / with-bounty projections (single source of truth for hashing)
;; ──────────────────────────────────────────────────────────────────────────────
;; The with-bounty artifacts (bounty-payable.v1, bounty-payable-backing.v1,
;; and the ADR-0006 with-bounty composition artifacts) are content-addressed
;; through these projections.  The economics namespaces delegate their
;; domain-hash call sites here so intent-based hashing (hash-with-intent),
;; direct domain-hash, and committed artifact roots can never drift.

(def with-bounty-policy-defaults
  "Stage A defaults for a with-bounty policy (mirror of
   resolver-sim.economics.with-bounty.policy/default-policy)."
  {:composition/type :economics/with-bounty
   :composition/version 1
   :bounty/on-ineligible :skip
   :bounty/on-calculation-failure :abort-bounty
   :bounty/on-unsupported-effect :abort-before-mutation
   :bounty/failure-mode :base-independent})

(defn project-bounty-payable
  "Canonical projection of a bounty-payable.v1 artifact: the committed payable
   identity fields, projected canonical-safe."
  [value _intent]
  (project-canonical-safe
   (select-keys value
                [:schema-version
                 :payable/id
                 :payable/distribution-root
                 :payable/award-id
                 :payable/beneficiary
                 :payable/amount
                 :payable/kind
                 :payable/lifecycle
                 :payable/evidence-references
                 :payable/context])))

(defn project-bounty-payable-backing
  "Canonical projection of a bounty-payable-backing.v1 artifact: the committed
   backing identity fields, projected canonical-safe."
  [value _intent]
  (project-canonical-safe
   (select-keys value
                [:schema-version
                 :backing/id
                 :backing/payable-root
                 :backing/payable-id
                 :backing/distribution-root
                 :backing/amount
                 :backing/source-allocations
                 :backing/kind
                 :backing/lifecycle
                 :backing/context])))

(defn project-with-bounty-policy
  "Canonical projection of a with-bounty policy: the normalised policy (defaults
   filled in) so identical authored policies with omitted defaults hash
   identically.  Matches normalize-with-bounty-policy exactly."
  [value _intent]
  (merge with-bounty-policy-defaults (or value {})))

(defn project-with-bounty-obligation
  "Versioned projection of a with-bounty obligation identity:
     [:bounty-payable operation-root bounty-id recipient token amount policy-root]"
  [{:keys [operation-root bounty-id recipient token amount policy-root]} _intent]
  [:bounty-payable operation-root bounty-id recipient token amount policy-root])

(defn project-with-bounty-invocation
  "Versioned projection of a with-bounty step invocation identity:
     [policy-root step-id index capability-ref]"
  [value _intent]
  [(:policy-root value) (:step/id value) (:index value) (:capability/ref value)])

(defn project-with-bounty-effect
  "Canonical projection of a single with-bounty effect, projected canonical-safe."
  [value _intent]
  (project-canonical-safe value))

(defn project-with-bounty-effect-set
  "Canonical projection of a with-bounty effect set: the ordered effect roots
   bound under a (possibly nil) base plan root."
  [[base-plan-root effect-roots] _intent]
  [base-plan-root (vec effect-roots)])

(def with-bounty-plan-projection-fields
  "Committed fields of a with-bounty-application-plan.v1 identity."
  [:schema-version
   :plan/policy-root
   :plan/base-operation-root
   :plan/base-result-root
   :plan/base-plan-root
   :plan/extensions-resolution-root
   :plan/adapter
   :plan/effects
   :plan/effect-roots
   :plan/combined-effect-root
   :plan/effect-schema-roots
   :plan/declared-maximum
   :plan/funding-available
   :plan/obligation-id
   :plan/no-duplicate-creation-key
   :plan/preconditions
   :plan/idempotency-key
   :plan/context])

(defn project-with-bounty-application-plan
  "Canonical projection of a with-bounty-application-plan.v1: the committed plan
   fields, projected canonical-safe (sets → sorted vectors)."
  [value _intent]
  (project-canonical-safe (select-keys value with-bounty-plan-projection-fields)))

(def with-bounty-transition-evidence-fields
  "Committed fields of a with-bounty transition evidence identity."
  [:transition/type
   :plan/root
   :effect-root
   :combined-effect-root
   :world-before-root
   :world-after-root
   :payable/roots
   :backing/roots
   :custody/adjustment-roots
   :idempotent?
   :context])

(defn project-with-bounty-transition-evidence
  "Canonical projection of a with-bounty transition evidence record, projected
   canonical-safe."
  [value _intent]
  (project-canonical-safe (select-keys value with-bounty-transition-evidence-fields)))

(def with-bounty-verification-basis-fields
  "Committed fields of a with-bounty-verification-basis.v1 identity."
  [:schema-version
   :basis/subject-root
   :basis/package-root
   :basis/artifact-root
   :basis/verification-contract
   :basis/verification-contract-version
   :basis/entrypoint
   :basis/invocation-parameters
   :basis/dependency-lockfile-root
   :basis/runtime-root
   :basis/environment-root
   :basis/vector-set-root
   :basis/resource-limit-profile
   :basis/expected-public-result-schema
   :basis/classification-policy-root])

(defn project-with-bounty-verification-basis
  "Canonical projection of a with-bounty-verification-basis.v1 artifact, projected
   canonical-safe."
  [value _intent]
  (project-canonical-safe (select-keys value with-bounty-verification-basis-fields)))

(defn project-with-bounty-public-result
  "Canonical public-result projection of a with-bounty evaluation result.
   Excludes replay inputs, invocation evidence, and plan/effect payloads —
   only the committed public roots and the classification remain."
  [{:keys [status receipt]} _intent]
  {:status status
   :composition/policy-root (get-in receipt [:composition/policy-root])
   :composition/base-operation-root (get-in receipt [:composition/base-operation-root])
   :extensions/resolution-root (get-in receipt [:extensions/resolution-root])
   :bounty/obligation-id (get-in receipt [:bounty/obligation-id])
   :bounty/effect-root (get-in receipt [:bounty/effect-root])
   :bounty/application-plan-root (get-in receipt [:bounty/application-plan-root])})

;; ──────────────────────────────────────────────────────────────────────────────
;; PRF Genesis Projections
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; protocol-genesis.v1 and chain-instance-genesis.v1 are canonical identity
;; artifacts (see resolver-sim.genesis). Their explicit versioned projections
;; select exactly the hash-bearing identity fields. Deployment/provenance
;; observations are rejected by the closed validator before hashing — they are
;; never silently dropped from the canonical preimage.

(def protocol-genesis-fields
  "Ordered identity fields of protocol-genesis.v1. The projection selects exactly
   these; the validator closes the top-level shape against any other key."
  [:genesis/schema
   :protocol/id
   :canonicalisation/root
   :semantics/root
   :governance/constitution-root
   :governance/evolution-policy-root
   :configuration/contract-root
   :evidence/contract-root
   :verification/contract-root
   :cross-domain/authority-policy-root])

(def chain-instance-genesis-control-plane-fields
  "Exactly-permitted keys of the :control-plane nested map of
   chain-instance-genesis.v1."
  [:address :runtime-code-keccak256])

(def chain-instance-genesis-governance-fields
  "Exactly-permitted keys of the :governance nested map of
   chain-instance-genesis.v1."
  [:authority-adapter :authority-adapter-code-keccak256
   :initial-authority-state-root])

(def chain-instance-genesis-fields
  "Ordered identity fields of chain-instance-genesis.v1 (top level)."
  [:genesis/schema
   :protocol/genesis-root
   :execution/chain-id
   :settlement/chain-id
   :control-plane
   :governance
   :configuration/initial-root])

(defn project-protocol-genesis
  "Canonical projection of protocol-genesis.v1: exactly the canonical identity
   fields, projected canonical-safe. Unknown keys never enter the preimage
   (they are rejected by the closed validator before hashing)."
  [value _intent]
  (project-canonical-safe (select-keys value protocol-genesis-fields)))

(defn project-chain-instance-genesis
  "Canonical projection of chain-instance-genesis.v1: exactly the canonical
   identity fields, projecting nested :control-plane and :governance to their
   exact sub-field sets. Unknown top-level or nested keys never enter the
   preimage."
  [value _intent]
  (let [cp (:control-plane value)
        gov (:governance value)
        base (select-keys value chain-instance-genesis-fields)]
    (project-canonical-safe
     (cond-> base
       (map? cp) (assoc :control-plane
                        (select-keys cp chain-instance-genesis-control-plane-fields))
       (map? gov) (assoc :governance
                         (select-keys gov chain-instance-genesis-governance-fields))))))

(def chain-configuration-fields
  "Ordered identity fields of chain-configuration.v1. All values are opaque
   sha256 reference roots to semantic sub-artefacts. The projection selects exactly
   these; the validator closes the shape against any other key."
  [:configuration/schema
   :module-registry/root
   :verifier-registry/root
   :evidence-policy/root
   :escrow-template-registry/root
   :parameter-policy/root
   :governance-policy/root
   :interoperability-policy/root])

(def chain-configuration-transition-fields
  "Ordered identity fields of chain-configuration-transition.v1 (top level)."
  [:transition/schema
   :protocol/genesis-root
   :target
   :configuration/parent-root
   :configuration/new-root
   :verifier-registry/root
   :epoch])

(def chain-configuration-transition-target-fields
  "Exactly-permitted keys of the nested :target map of
   chain-configuration-transition.v1."
  [:target/type :target/root])

(defn project-chain-configuration
  "Canonical projection of chain-configuration.v1: exactly the canonical identity
   fields, projected canonical-safe. Unknown keys never enter the preimage."
  [value _intent]
  (project-canonical-safe (select-keys value chain-configuration-fields)))

(defn project-chain-configuration-transition
  "Canonical projection of chain-configuration-transition.v1: exactly the canonical
   identity fields, projecting nested :target to its exact sub-field set."
  [value _intent]
  (let [target (:target value)
        base (select-keys value chain-configuration-transition-fields)]
    (project-canonical-safe
     (cond-> base
       (map? target) (assoc :target
                            (select-keys target chain-configuration-transition-target-fields))))))

(def hash-intents
  "Map of hash intent keywords to their Intent Registry Contracts.
   Each contract explicitly declares the intent name, description,
   includes, exclusions, projection function, domain tag, and version.

   Usage: (hash-with-intent {:hash/intent :world-structure} data)

   Per INTENT_REGISTRY_SPEC_V1, each field is required."
  {:world-structure
   {:intent/name        :world-structure
    :intent/domain-tag  "WORLD_STATE_V1"
    :intent/description "Structural identity of system state for evidence anchoring"
    :intent/includes    #{:domain-state :positions :balances :config
                          :oracle-state :resolver-registry :bond-state
                          :dispute-state :escrow-state :time-context}
    :intent/excludes    #{:module-implementations :runtime-values}
    :intent/projection-fn project-world-to-structure-view
    :intent/version     1}

   :evidence-record
   {:intent/name        :evidence-record
    :intent/domain-tag  "EVIDENCE_RECORD_V1"
    :intent/description "Content identity of an individual evidence record"
    :intent/includes    #{:attribution :action :result :context
                          :artifact-kind :temporal-context :sub-hashes}
    ;; :functions is a hard runtime-value rejection (defense-in-depth): evidence
    ;; records are finalized data and must not carry live runtime objects. This
    ;; complements (but does not replace) the writer-boundary rule that
    ;; finalized evidence schemas simply reject runtime values before they are
    ;; ever persisted.
    :intent/excludes    #{:evidence-hash :timestamp :chain-metadata :functions}
    :intent/projection-fn project-identity
    :intent/version     1}

   :evidence-content
   {:intent/name        :evidence-content
    :intent/domain-tag  "EVIDENCE_CONTENT_V1"
    :intent/description "JSON-round-trippable content hash of an evidence record"
    :intent/includes    #{:serialized-content :evidence-fields :artifact-body}
    ;; Keywords and hash-like keys are NOT excluded here: the projection
    ;; (project-for-content-hash) already normalizes them (keyword→string,
    ;; hash-keys preserved as content), so excluding them would reject
    ;; legitimate JSON-round-trippable evidence content. Only semantic
    ;; exclusions belong in :intent/excludes (see project-world-to-structure-view).
    :intent/excludes    #{:chain-metadata :timestamps}
    :intent/projection-fn project-for-content-hash
    :intent/version     1}

   :evidence-chain
   {:intent/name        :evidence-chain
    :intent/domain-tag  "EVIDENCE_CHAIN_V1"
    :intent/description "Evidence chain linking structure for audit trails"
    :intent/includes    #{:chain-links :registry-structure :prev-hash
                          :chain-seq :cursor/final-self-hash
                          :evidence/chain-self-hash}
    :intent/excludes    #{:artifact-content :evidence-payload :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :evidence-chain-link-v1
   {:intent/name        :evidence-chain-link-v1
    :intent/domain-tag  "EVIDENCE_CHAIN_LINK_V1"
    :intent/description "Versioned evidence-chain link committing content, sequence, and predecessor"
    :intent/includes    #{:evidence-hash :chain-seq :prev-hash :chain-hash-scheme}
    :intent/excludes    #{:evidence-payload :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :run-evidence-hash-set-v1
   {:intent/name        :run-evidence-hash-set-v1
    :intent/domain-tag  "RUN_EVIDENCE_HASH_SET_V1"
    :intent/description "Canonical sorted-set commitment for a run's evidence content hashes"
    :intent/includes    #{:evidence-hashes}
    :intent/excludes    #{:artifact-order :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :evidence-hash-set
   {:intent/name        :evidence-hash-set
    :intent/domain-tag  "EVIDENCE_HASH_SET_V1"
    :intent/description "Schema-bearing canonical commitment to evidence hash identities"
    :intent/includes    #{:schema-version :hash-algorithm :count :hashes}
    :intent/excludes    #{:timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :evidence-finalization-v2
   {:intent/name        :evidence-finalization-v2
    :intent/domain-tag  "EVIDENCE_FINALIZATION_V2"
    :intent/description "Canonical payload identity for evidence-finalization.v2"
    :intent/includes    #{:finalization-envelope}
    :intent/excludes    #{:artifact-id :self-hash :signatures :timestamps}
    :intent/projection-fn project-self-hash-stripped
    :intent/version     2}

   :runner-finalization
   {:intent/name        :runner-finalization
    :intent/domain-tag  "RUNNER_FINALIZATION_V1"
    :intent/description "Immutable local runner identity and execution-result commitment"
    :intent/includes    #{:runner-selection :runner-local :execution-result}
    :intent/excludes    #{:artifact-path :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :run-package-index
   {:intent/name        :run-package-index
    :intent/domain-tag  "RUN_PACKAGE_INDEX_V1"
    :intent/description "Immutable references that define a runnable structured run package"
    :intent/includes    #{:run-id :bundle-root-hash :artifacts}
    :intent/excludes    #{:artifact-path :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :manifest
   {:intent/name        :manifest
    :intent/domain-tag  "MANIFEST_V1"
    :intent/description "Bundle manifest identity for artifact packaging"
    :intent/includes    #{:manifest-metadata :bundle-structure :schema-version}
    :intent/excludes    #{:content-payloads :individual-artifacts}
    :intent/projection-fn project-identity
    :intent/version     1}

   :protocol-state
   {:intent/name        :protocol-state
    :intent/domain-tag  "PROTOCOL_STATE_V1"
    :intent/description "Deterministic protocol-state snapshot for reproducibility"
    :intent/includes    #{:force-authorisations :force-authorisations-consumed}
    :intent/excludes    #{:world-state :traces :evidence-registry}
    :intent/projection-fn project-identity
    :intent/version     1}

   :bundle-root
   {:intent/name        :bundle-root
    :intent/domain-tag  "BUNDLE_ROOT_V1"
    :intent/description "Top-level benchmark bundle commitment. Authoritative projection is
resolver-sim.benchmark.integrity/hashable-evidence (field selection excluding :timestamp,
:evidence/hash, :evidence/signature, :evidence/public-key-path,
:benchmark/artifact-index, :repo, :run/manifest/:manifest/at
and :results/:scenario/artifacts), composed with project-world-to-structure-view (runtime-type
normalization). :evidence/commitment-version is deliberately NOT excluded: when present it is
committed into the hash, binding the bundle to its interpretation scheme. The
:intent/includes/:intent/excludes below are advisory semantic labels only; they
are NOT the field-level rule and the hash is not recomputed to match them.
:evidence/hash is the single stored commitment field; :bundle-root is this hash intent's
name (an alias)."
    :intent/includes    #{:benchmark-metadata :environment :evidence-aggregates
                          :reproducibility :benchmark-certification :run/manifest}
    :intent/excludes    #{:runtime-posthash-fields :operational-locations
                          :materialization-metadata :runtime-types}
    ;; Benchmark output contains exact ratios and runtime collections; normalize
    ;; them before canonical encoding rather than relying on lossy coercion.
    :intent/projection-fn project-world-to-structure-view
    :intent/version     2}

   :registry
   {:intent/name        :registry
    :intent/domain-tag  "REGISTRY_V1"
    :intent/description "Evidence registry commitment for artifact catalog"
    :intent/includes    #{:registry-index :artifact-catalog :commitment-root}
    :intent/excludes    #{:artifact-content :detailed-evidence :world-state}
    :intent/projection-fn project-identity
    :intent/version     1}

   :provenance
   {:intent/name        :provenance
    :intent/domain-tag  "PROVENANCE_V1"
    :intent/description "Provenance lineage and verification metadata"
    :intent/includes    #{:provenance-lineage :verification-metadata :links}
    :intent/excludes    #{:raw-evidence-content :world-snapshots}
    :intent/projection-fn project-identity
    :intent/version     1}

   :evm-projection
   {:intent/name        :evm-projection
    :intent/domain-tag  "EVM_PROJECTION_V1"
    :intent/description "EVM-compatible world subset for cross-system comparison"
    :intent/includes    #{:comparable-world-subset :computed-invariants}
    :intent/excludes    #{:sim-only-fields :module-implementations}
    :intent/projection-fn project-world-to-structure-view
    :intent/version     1}

   :state-diff
   {:intent/name        :state-diff
    :intent/domain-tag  "STATE_DIFF_V1"
    :intent/description "Structural diff state hash for trace comparisons"
    :intent/includes    #{:diff-changes :path-stripped-values}
    :intent/excludes    #{:before-values :after-values :raw-world-state}
    :intent/projection-fn project-identity
    :intent/version     1}

   :params-manifest
   {:intent/name        :params-manifest
    :intent/domain-tag  "PARAMS_MANIFEST_V1"
    :intent/description "Parameter manifest for multi-epoch reproducibility"
    :intent/includes    #{:sim-params :config-params :run-params}
    :intent/excludes    #{:runtime-state :evidence-data}
    :intent/projection-fn project-identity
    :intent/version     1}

   :invariant-attestation
   {:intent/name        :invariant-attestation
    :intent/domain-tag  "INVARIANT_ATTESTATION_V1"
    :intent/description "Per-step invariant attestation: which invariants held, which failed"
    :intent/includes    #{:step :invariants :passed :failed :invariant-set-hash}
    :intent/excludes    #{:full-world-state :action-detail :raw-trace}
    :intent/projection-fn project-identity
    :intent/version     1}

   :projection-evidence
   {:intent/name        :projection-evidence
    :intent/domain-tag  "PROJECTION_EVIDENCE_V1"
    :intent/description "Projection hash paired with world hash for cross-system comparison"
    :intent/includes    #{:step :world-hash :projection-hash :projection-version}
    :intent/excludes    #{:full-world-state :internal-fields}
    :intent/projection-fn project-identity
    :intent/version     1}

   :checkpoint-evidence
   {:intent/name        :checkpoint-evidence
    :intent/domain-tag  "CHECKPOINT_EVIDENCE_V1"
    :intent/description "Attestable checkpoint with world hash and chain position"
    :intent/includes    #{:checkpoint-id :event-seq :world-hash :chain-head}
    :intent/excludes    #{:full-world-state :trace-detail}
    :intent/projection-fn project-identity
    :intent/version     1}

   :scenario
   {:intent/name        :scenario
    :intent/domain-tag  "SCENARIO_V1"
    :intent/description "Stable content hash of a scenario definition for cross-runner scenario identification"
    :intent/includes    #{:scenario-id :scenario-path :protocol :dispatcher-id :normalized-scenario}
    :intent/excludes    #{:runtime-metadata :host-info :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :run-overview
   {:intent/name        :run-overview
    :intent/domain-tag  "RUN_OVERVIEW_V1"
    :intent/description "Normalized run overview for runner comparison and consensus"
    :intent/includes    #{:overview-metadata :scenario-results :totals :suite-info}
    :intent/excludes    #{:execution/raw :diagnostics :timestamps :absolute-paths :host-info}
    :intent/projection-fn project-identity
    :intent/version     1}

   :benchmark-certification
   {:intent/name        :benchmark-certification
    :intent/domain-tag  "BENCHMARK_CERTIFICATION_V1"
    :intent/description "Benchmark run certification with invariant summary"
    :intent/includes    #{:benchmark-id :scenario-count :all-invariants-pass
                          :final-state-hash :evidence-chain-root :invariant-summary}
    :intent/excludes    #{:individual-results :detailed-evidence :traces}
    :intent/projection-fn project-identity
    :intent/version     1}

   :intent-dsl
   {:intent/name        :intent-dsl
    :intent/domain-tag  "INTENT_DSL_V1"
    :intent/description "Canonical identity of an INTENT_DSL_SPEC_V1 intent object"
    :intent/includes    #{:intent/type :intent/version :intent/purpose :intent/scope
                          :intent/inputs :intent/constraints :intent/output}
    :intent/excludes    #{:runtime-values :functions}
    :intent/projection-fn project-intent-dsl
    :intent/version     1}

   :intent-registry-entry
   {:intent/name        :intent-registry-entry
    :intent/domain-tag  "INTENT_REGISTRY_ENTRY_V1"
    :intent/description "Canonical identity of one registered intent contract"
    :intent/includes    #{:intent/name :intent/domain-tag :intent/description
                          :intent/includes :intent/excludes :intent/projection-fn
                          :intent/version}
    :intent/excludes    #{:runtime-values}
    :intent/projection-fn project-intent-registry-entry
    :intent/version     1}

   :intent-registry
   {:intent/name        :intent-registry
    :intent/domain-tag  "INTENT_REGISTRY_V1"
    :intent/description "Canonical identity of an intent registry artifact"
    :intent/includes    #{:registry-version :intent-definitions :intent-hashes}
    :intent/excludes    #{:registry-hash :runtime-values}
    :intent/projection-fn project-intent-registry
    :intent/version     1}

   :projection-definition
   {:intent/name        :projection-definition
    :intent/domain-tag  "PROJECTION_DEFINITION_V1"
    :intent/description "Canonical identity of one projection definition"
    :intent/includes    #{:id :version :projection-type :intent-types :intent-purposes
                          :source :include-paths :exclude-paths :transforms
                          :output :claims :depends-on}
    :intent/excludes    #{:canonical-hash :runtime-values :functions}
    :intent/projection-fn project-projection-definition
    :intent/version     1}

   :projection-definition-registry
   {:intent/name        :projection-definition-registry
    :intent/domain-tag  "PROJECTION_DEFINITION_REGISTRY_V1"
    :intent/description "Canonical identity of a projection definition registry artifact"
    :intent/includes    #{:registry-version :projection-definitions :definition-hashes}
    :intent/excludes    #{:registry-hash :runtime-values}
    :intent/projection-fn project-projection-definition-registry
    :intent/version     1}

   :projection-artifact
   {:intent/name        :projection-artifact
    :intent/domain-tag  "PROJECTION_ARTIFACT_V1"
    :intent/description "Canonical identity of a projection artifact excluding its self hash"
    :intent/includes    #{:schema-version :projection-id :projection-type
                          :projection-version :intent :projection-definition-hash
                          :source :projection :claims}
    :intent/excludes    #{:projection-hash :metadata :runtime-values}
    :intent/projection-fn project-projection-artifact
    :intent/version     1}

   :pro-rata-allocation-result
   {:intent/name        :pro-rata-allocation-result
    :intent/domain-tag  "PRO_RATA_ALLOCATION_RESULT_V1"
    :intent/description "Canonical identity of a pro-rata allocation result artifact excluding its self hash"
    :intent/includes    #{:schema-version :artifact-kind :allocation-result-id
                          :allocation-result-type :allocation-result-version
                          :projection-artifact-hash :projection-definition-id
                          :projection-definition-hash :source :provenance
                          :allocation-result :shortfall-outcome :claims
                          :invariant-links}
    :intent/excludes    #{:allocation-result-hash :metadata :external-refs :runtime-values}
    :intent/projection-fn project-pro-rata-allocation-result
    :intent/version     1}

   :priority-order-v1
   {:intent/name        :priority-order-v1
    :intent/domain-tag  "PRIORITY_ORDER_V1"
    :intent/description "Canonical identity of a priority-order.v1 artifact body excluding its self content-addressing envelope"
    :intent/includes    #{:artifact/kind :artifact/version :subjects
                          :subject-priority-keys :priority-classes :comparison-basis
                          :comparison-contract :tie-policy :unclassified-policy
                          :derivation :subject-set-root :comparison-basis-root
                          :priority-classes-root}
    :intent/excludes    #{:artifact/content-hash :artifact/preimage :artifact/metadata
                          :metadata :runtime-values :functions}
    :intent/projection-fn project-priority-order
    :intent/version     1}

   :claim-definition
   {:intent/name        :claim-definition
    :intent/domain-tag  "CLAIM_DEFINITION"
    :intent/description "Canonical identity of one claim definition"
    :intent/includes    #{:id :version :category :inputs
                          :evaluation :outputs :depends-on}
    :intent/excludes    #{:canonical-hash :runtime-values :functions
                          :cached-values :generated-metadata :description}
    :intent/projection-fn project-claim-definition
    :intent/version     1}

   :claim-definition-conceptual
   {:intent/name        :claim-definition-conceptual
    :intent/domain-tag  "CONCEPT_CLAIM_DEFINITION_V1"
    :intent/description "Self-aware concept hash transitively including resolved dependency hashes"
    :intent/includes    #{:id :version :category :inputs
                          :evaluation :outputs :depends-on}
    :intent/excludes    #{:canonical-hash :concept-hash :runtime-values :functions
                          :cached-values :generated-metadata :description}
    :intent/projection-fn project-claim-definition-conceptual
    :intent/version     1}

   :attestor
   {:intent/name        :attestor
    :intent/domain-tag  "ATTESTOR"
    :intent/description "Canonical identity of one attestor registry entry"
    :intent/includes    #{:id :type :status :verification :delegates :key-history}
    :intent/excludes    #{:canonical-hash :attestor-hash :display-name :metadata
                          :runtime-values :cached-verification-data :private-keys}
    :intent/projection-fn project-attestor
    :intent/version     1}

   :evidence-node
   {:intent/name        :evidence-node
    :intent/domain-tag  "EVIDENCE_NODE_V1"
    :intent/description "Canonical identity of an execution evidence node"
    :intent/includes    #{:schema-version :parent-hashes :bootstrap-roots
                          :execution :result :evidence :attestations :extensions}
    :intent/excludes    #{:node-id :node-hash :timestamp :policy-output
                          :visible-failures :filtered-output :runtime-values}
    :intent/projection-fn project-evidence-node
    :intent/version     1}

   :creation-provenance
   {:intent/name        :creation-provenance
    :intent/domain-tag  "CREATION_PROVENANCE_V1"
    :intent/description "Domain-separated identity for a creation provenance commitment"
    :intent/includes    #{:creation/provenance}
    :intent/excludes    #{}
    :intent/projection-fn project-creation-provenance
    :intent/version     1}

   :decision-evidence
   {:intent/name        :decision-evidence
    :intent/domain-tag  "DECISION_EVIDENCE_V1"
    :intent/description "Structured record of a decision with alternatives and selection"
    :intent/includes    #{:decision-id :step :alternatives :selected :reasoning
                          :caller :workflow-id}
    :intent/excludes    #{:full-world-state :trace-detail :internal-fields}
    :intent/projection-fn project-identity
    :intent/version     1}

   :invariant-failure
   {:intent/name        :invariant-failure
    :intent/domain-tag  "INVARIANT_FAILURE_V1"
    :intent/description "Evidence recorded when an invariant check fails and halts the simulation"
    :intent/includes    #{:step :scenario-id :invariant-ids :details :halt-reason}
    :intent/excludes    #{:full-world-state :raw-trace :internal-state}
    :intent/projection-fn project-identity
    :intent/version     1}

   :startup-validation
   {:intent/name        :startup-validation
    :intent/domain-tag  "STARTUP_VALIDATION_V1"
    :intent/description "Startup registry validation evidence — records that all semantic registries passed validation at system start"
    :intent/includes    #{:registry-count :valid? :registry-summary :generated-at :schema-version}
    :intent/excludes    #{:registry-detail :full-registry-data}
    :intent/projection-fn project-identity
    :intent/version     1}

   :claim-result
   {:intent/name        :claim-result
    :intent/domain-tag  "CLAIM_RESULT_V1"
    :intent/description "Canonical identity of a claim evaluation result"
    :intent/includes    #{:claim-id :claim-definition-hash :holds? :status}
    :intent/excludes    #{:violations :evidence-references :depends-on :metadata}
    :intent/projection-fn project-claim-result
    :intent/version     1}

   :attestation
   {:intent/name        :attestation
    :intent/domain-tag  "ATTESTATION_V1"
    :intent/description "Canonical identity of an attestation record"
    :intent/includes    #{:attestation-id :attestor :subject :claim :timestamp}
    :intent/excludes    #{:signature :metadata :canonical-hash}
    :intent/projection-fn project-attestation
    :intent/version     1}

   :attestation-record
   {:intent/name        :attestation-record
    :intent/domain-tag  "ATTESTATION_RECORD_V1"
    :intent/description "Canonical identity of a content-addressed attestation record, excluding self-hash and signature"
    :intent/includes    #{:schema-version
                          :attestation/subject-hash :attestation/subject-kind
                          :attestation/claim-id :attestation/claim-result
                          :attestation/attestor-id :attestation/signing-key-id
                          :attestation/signed-at :attestation/provenance}
    :intent/excludes    #{:attestation/id :attestation/hash :attestation/signature
                          :attestation/metadata :registry/indexed-at}
    :intent/projection-fn project-attestation-record
    :intent/version     1}

   :execution-definition
   {:intent/name        :execution-definition
    :intent/domain-tag  "EXECUTION_DEFINITION_V1"
    :intent/description "Canonical identity of an execution registry definition entry"
    :intent/includes    #{:id :version :kind :runner :entry :execution/type :execution/mode :claims}
    :intent/excludes    #{:description :depends-on :canonical-hash}
    :intent/projection-fn project-execution-definition
    :intent/version     1}

   :action
   {:intent/name        :action
    :intent/domain-tag  "ACTION_V1"
    :intent/description "Canonical identity of a normalized action payload. Includes normalized action content minus self-hash fields."
    :intent/includes    #{:action/type :action/content}
    :intent/excludes    #{:type :timestamp :metadata :trace :runtime-values
                          :canonical-hash :hash :node-hash}
    :intent/projection-fn project-action
    :intent/version     2}

   :action-at
   {:intent/name        :action-at
    :intent/domain-tag  "ACTION_AT_V1"
    :intent/description "Canonical identity of an action occurrence at a specific execution point."
    :intent/includes    #{:action-hash :step :block-time}
    :intent/excludes    #{:action :metadata :world-before :world-after :runtime-values}
    :intent/projection-fn project-action-at
    :intent/version     1}

   :stability/snapshot
   {:intent/name        :stability/snapshot
    :intent/domain-tag  "STABILITY_SNAPSHOT_V1"
    :intent/description "Canonical snapshot of source file contents for stability tracking.
                         Takes {:files {\"path\" \"content\" ...}} and produces a sorted,
                         deterministic hash. Used by STABILITY_MANIFEST.edn and
                         bb stability:check."
    :intent/includes    #{:files :paths :contents}
    :intent/excludes    #{:metadata :timestamps :runtime-state}
    :intent/projection-fn project-stability-snapshot
    :intent/version     1}

   :trust-sequence-definition
   {:intent/name        :trust-sequence-definition
    :intent/domain-tag  "TRUST_SEQUENCE_DEFINITION_V1"
    :intent/description "Canonical identity of a trust-sequence-definition artifact"
    :intent/includes    #{:schema-version :id :provider :steps}
    :intent/excludes    #{:root :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :procedure-execution-witness
   {:intent/name        :procedure-execution-witness
    :intent/domain-tag  "PROCEDURE_EXECUTION_WITNESS_V1"
    :intent/description "Canonical identity of a procedure-execution-witness artifact"
    :intent/includes    #{:schema-version :id :definition-root :initial-input-root :steps :result-root}
    :intent/excludes    #{:root :verification :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :pool-availability-v2
   {:intent/name        :pool-availability-v2
    :intent/domain-tag  "POOL_AVAILABILITY_V2"
    :intent/description "Canonical identity of a pool-availability v2 snapshot artifact, including predecessor binding"
    :intent/includes    #{:artifact/type :pool/id :pool/kind :pool/owner-id
                          :pool/state-root :pool/policy-root
                          :pool/snapshot-time
                          :pool/gross-amount :pool/reserved-amount
                          :pool/protected-amount :pool/available-amount
                          :pool/liability-roots :pool/reservation-roots
                          :pool/predecessor-hash}
    :intent/excludes    #{:artifact/hash :metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :pool-reservation
   {:intent/name        :pool-reservation
    :intent/domain-tag  "POOL_RESERVATION_V1"
    :intent/description "Canonical identity of a pool reservation artifact"
    :intent/includes    #{:artifact/type :reservation/id :reservation/pool-root
                          :reservation/amount :reservation/purpose-root}
    :intent/excludes    #{:artifact/hash :metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :award-calculation-v2
   {:intent/name        :award-calculation-v2
    :intent/domain-tag  "AWARD_CALCULATION_V2"
    :intent/description "Canonical identity of an award calculation v2 artifact, including eligibility binding roots"
    :intent/includes    #{:artifact/type :award/id :award/policy-root
                          :award/pool-availability-root
                          :award/claim-set-root :award/evidence-set-root
                          :award/beneficiary-id :award/calculation-time
                          :award/amount :award/scale
                          :award/calculation-components
                          :award/eligibility-result
                          :award/eligibility-policy-root
                          :award/check-set-root
                          :award/mode}
    :intent/excludes    #{:artifact/hash :metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :check-set
   {:intent/name        :check-set
    :intent/domain-tag  "CHECK_SET_V1"
    :intent/description "Canonical root of a sorted, deduplicated set of eligibility check IDs"
    :intent/includes    #{:check/ids}
    :intent/excludes    #{:metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :award-policy
   {:intent/name        :award-policy
    :intent/domain-tag  "AWARD_POLICY_V1"
    :intent/description "Canonical identity of an award policy artifact committing the required eligibility check-set root"
    :intent/includes    #{:artifact/type :policy/id :policy/required-check-ids
                          :policy/check-set-root}
    :intent/excludes    #{:artifact/hash :metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :claim-set
   {:intent/name        :claim-set
    :intent/domain-tag  "CLAIM_SET_V1"
    :intent/description "Canonical root of a sorted, deduplicated set of claim hashes"
    :intent/includes    #{:claim/roots}
    :intent/excludes    #{:metadata}
    :intent/projection-fn project-identity
    :intent/version     1}

   :lab-parameter-root
   {:intent/name        :lab-parameter-root
    :intent/domain-tag  "LAB_PARAMETER_ROOT_V1"
    :intent/description "Canonical parameter-root of validated Assurance Lab inputs for a run"
    :intent/includes    #{:inputs :parameters}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :lab-withdrawal-fcfs
   {:intent/name        :lab-withdrawal-fcfs
    :intent/domain-tag  "LAB_WITHDRAWAL_FCFS_V1"
    :intent/description "Canonical witness binding of the Assurance Lab FCFS sequential-withdrawal outcome"
    :intent/includes    #{:mechanism :available :requested-total :filled-total
                          :deferred-total :rows}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :fail-action-policy
   {:intent/name        :fail-action-policy
    :intent/domain-tag  "FAIL_ACTION_POLICY_V1"
    :intent/description "Committed root of a declared pro-rata fail-action policy: how a partial-fill shortfall is treated per bucket (deferred/haircut) when the fill cannot be settled in full"
    :intent/includes    #{:mode :deferred-policy :haircut-policy :treatment :priority}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-identity
    :intent/version     1}

   :bounty-payable-v1
   {:intent/name        :bounty-payable-v1
    :intent/domain-tag  "BOUNTY_PAYABLE_V1"
    :intent/description "Content-addressed root of a bounty-payable.v1 artifact: the committed payable identity"
    :intent/includes    #{:schema-version :payable/id :payable/distribution-root
                          :payable/award-id :payable/beneficiary :payable/amount
                          :payable/kind :payable/lifecycle
                          :payable/evidence-references :payable/context}
    :intent/excludes    #{:payable/hash :runtime-values :functions}
    :intent/projection-fn project-bounty-payable
    :intent/version     1}

   :bounty-payable-backing-v1
   {:intent/name        :bounty-payable-backing-v1
    :intent/domain-tag  "BOUNTY_PAYABLE_BACKING_V1"
    :intent/description "Content-addressed root of a bounty-payable-backing.v1 artifact: the committed backing identity"
    :intent/includes    #{:schema-version :backing/id :backing/payable-root
                          :backing/payable-id :backing/distribution-root
                          :backing/amount :backing/source-allocations
                          :backing/kind :backing/lifecycle :backing/context}
    :intent/excludes    #{:backing/hash :runtime-values :functions}
    :intent/projection-fn project-bounty-payable-backing
    :intent/version     1}

   :with-bounty-policy-v1
   {:intent/name        :with-bounty-policy-v1
    :intent/domain-tag  "WITH_BOUNTY_POLICY_V1"
    :intent/description "Content-addressed root of a normalised with-bounty policy: the declared contract of a with-bounty composition"
    :intent/includes    #{:composition/type :composition/version
                          :bounty/on-ineligible :bounty/on-calculation-failure
                          :bounty/on-unsupported-effect :bounty/failure-mode
                          :base :bounty}
    :intent/excludes    #{:policy/root :runtime-values :functions}
    :intent/projection-fn project-with-bounty-policy
    :intent/version     1}

   :with-bounty-invocation-v1
   {:intent/name        :with-bounty-invocation-v1
    :intent/domain-tag  "WITH_BOUNTY_INVOCATION_V1"
    :intent/description "Deterministic identity of one with-bounty step invocation (eligibility or amount)"
    :intent/includes    #{:policy-root :step/id :index :capability/ref}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-with-bounty-invocation
    :intent/version     1}

   :with-bounty-obligation-v1
   {:intent/name        :with-bounty-obligation-v1
    :intent/domain-tag  "WITH_BOUNTY_OBLIGATION_V1"
    :intent/description "Deterministic obligation identity of a with-bounty payable"
    :intent/includes    #{:operation-root :bounty-id :recipient :token :amount :policy-root}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-with-bounty-obligation
    :intent/version     1}

   :with-bounty-effect-v1
   {:intent/name        :with-bounty-effect-v1
    :intent/domain-tag  "WITH_BOUNTY_EFFECT_V1"
    :intent/description "Content-addressed root of a single validated with-bounty effect"
    :intent/includes    #{:effect/contract :effect/kind :effect/params}
    :intent/excludes    #{:effect/root :runtime-values :functions}
    :intent/projection-fn project-with-bounty-effect
    :intent/version     1}

   :with-bounty-effect-set-v1
   {:intent/name        :with-bounty-effect-set-v1
    :intent/domain-tag  "WITH_BOUNTY_EFFECT_SET_V1"
    :intent/description "Combined with-bounty effect-set root: base plan root plus the ordered effect roots"
    :intent/includes    #{:base-plan-root :effect-roots}
    :intent/excludes    #{:runtime-values :functions :timestamps}
    :intent/projection-fn project-with-bounty-effect-set
    :intent/version     1}

   :with-bounty-application-plan-v1
   {:intent/name        :with-bounty-application-plan-v1
    :intent/domain-tag  "WITH_BOUNTY_APPLICATION_PLAN_V1"
    :intent/description "Content-addressed root of a with-bounty application plan committing creation preconditions and the combined effect set"
    :intent/includes    #{:schema-version :plan/policy-root :plan/base-operation-root
                          :plan/base-result-root :plan/base-plan-root
                          :plan/extensions-resolution-root :plan/adapter
                          :plan/effects :plan/effect-roots
                          :plan/combined-effect-root :plan/effect-schema-roots
                          :plan/declared-maximum :plan/funding-available
                          :plan/obligation-id :plan/no-duplicate-creation-key
                          :plan/preconditions :plan/idempotency-key :plan/context}
    :intent/excludes    #{:plan/hash :runtime-values :functions}
    :intent/projection-fn project-with-bounty-application-plan
    :intent/version     1}

   :with-bounty-transition-evidence-v1
   {:intent/name        :with-bounty-transition-evidence-v1
    :intent/domain-tag  "WITH_BOUNTY_TRANSITION_EVIDENCE_V1"
    :intent/description "Content-addressed transition evidence binding a with-bounty application plan to the resulting protocol transition"
    :intent/includes    #{:transition/type :plan/root :effect-root
                          :combined-effect-root :world-before-root
                          :world-after-root :payable/roots :backing/roots
                          :custody/adjustment-roots :idempotent? :context}
    :intent/excludes    #{:transition/hash :runtime-values :functions}
    :intent/projection-fn project-with-bounty-transition-evidence
    :intent/version     1}

   :with-bounty-verification-basis-v1
   {:intent/name        :with-bounty-verification-basis-v1
    :intent/domain-tag  "WITH_BOUNTY_VERIFICATION_BASIS_V1"
    :intent/description "Content-addressed root of a with-bounty verification basis: exactly what a verifier evaluated"
    :intent/includes    #{:schema-version :basis/subject-root :basis/package-root
                          :basis/artifact-root :basis/verification-contract
                          :basis/verification-contract-version :basis/entrypoint
                          :basis/invocation-parameters
                          :basis/dependency-lockfile-root :basis/runtime-root
                          :basis/environment-root :basis/vector-set-root
                          :basis/resource-limit-profile
                          :basis/expected-public-result-schema
                          :basis/classification-policy-root}
    :intent/excludes    #{:basis/root :runtime-values :functions}
    :intent/projection-fn project-with-bounty-verification-basis
    :intent/version     1}

   :with-bounty-public-result-v1
   {:intent/name        :with-bounty-public-result-v1
    :intent/domain-tag  "WITH_BOUNTY_PUBLIC_RESULT_V1"
    :intent/description "Canonical public-result root of a with-bounty evaluation for verifier comparison"
    :intent/includes    #{:status :composition/policy-root
                          :composition/base-operation-root
                          :extensions/resolution-root :bounty/obligation-id
                          :bounty/effect-root :bounty/application-plan-root}
    :intent/excludes    #{:replay/inputs :invocation-evidence :diagnostics}
    :intent/projection-fn project-with-bounty-public-result
    :intent/version     1}

   :confidence-composition-v1
   {:intent/name        :confidence-composition-v1
    :intent/domain-tag  "CONFIDENCE_COMPOSITION_V1"
    :intent/description "Hash-bound consecutive concatenation of confidence components bound to a :purpose (canonical-value-sequence.v1 contract)"
    :intent/includes    #{:encoding-contract :purpose :component-count :components}
    :intent/excludes    #{:timestamps :runtime-values :functions}
    :intent/projection-fn project-identity
    :intent/version     1}

   :research-command-trace-v1
   {:intent/name        :research-command-trace-v1
    :intent/domain-tag  "RESEARCH_COMMAND_TRACE_V1"
    :intent/description "Legacy research-command trace root (v1, DEPRECATED — use research-command-trace-v2 / bound-sequence)"
    :intent/includes    #{:command-id :commands}
    :intent/excludes    #{:timestamps :runtime-values :functions}
    :intent/projection-fn project-identity
    :intent/version     1}

   :research-command-trace-v2
   {:intent/name        :research-command-trace-v2
    :intent/domain-tag  "RESEARCH_COMMAND_TRACE_V2"
    :intent/description "Research-command-trace.v2 root over a canonical-value-sequence.v1 commitment with an explicit :purpose"
    :intent/includes    #{:trace/schema-version :trace/purpose :trace/component-count
                          :trace/components}
    :intent/excludes    #{:trace/root :timestamps :runtime-values :functions}
    :intent/projection-fn project-identity
    :intent/version     1}

   :prf-protocol-genesis-v1
   {:intent/name        :prf-protocol-genesis-v1
    :intent/domain-tag  "PRF_PROTOCOL_GENESIS_V1"
    :intent/description "Canonical SHA-256 identity of a protocol-genesis.v1 constitutional protocol artifact"
    :intent/includes    #{:genesis/schema :protocol/id :canonicalisation/root
                          :semantics/root :governance/constitution-root
                          :governance/evolution-policy-root :configuration/contract-root
                          :evidence/contract-root :verification/contract-root
                          :cross-domain/authority-policy-root}
    :intent/excludes    #{:runtime-values :functions :deployment-metadata :timestamps}
    :intent/projection-fn project-protocol-genesis
    :intent/version     1}

   :prf-chain-instance-genesis-v1
   {:intent/name        :prf-chain-instance-genesis-v1
    :intent/domain-tag  "PRF_CHAIN_INSTANCE_GENESIS_V1"
    :intent/description "Canonical SHA-256 identity of a chain-instance-genesis.v1 execution instance"
    :intent/includes    #{:genesis/schema :protocol/genesis-root :execution/chain-id
                          :settlement/chain-id :control-plane :governance
                          :configuration/initial-root}
    :intent/excludes    #{:runtime-values :functions :deployment-metadata
                          :block-context :timestamps}
    :intent/projection-fn project-chain-instance-genesis
    :intent/version     1}

   :prf-chain-configuration-v1
   {:intent/name        :prf-chain-configuration-v1
    :intent/domain-tag  "PRF_CHAIN_CONFIGURATION_V1"
    :intent/description "Canonical SHA-256 identity of a chain-configuration.v1 semantic configuration state"
    :intent/includes    #{:configuration/schema
                          :module-registry/root :verifier-registry/root
                          :evidence-policy/root :escrow-template-registry/root
                          :parameter-policy/root :governance-policy/root
                          :interoperability-policy/root}
    :intent/excludes    #{:runtime-values :functions :deployment-metadata :timestamps}
    :intent/projection-fn project-chain-configuration
    :intent/version     1}

   :prf-chain-configuration-transition-v1
   {:intent/name        :prf-chain-configuration-transition-v1
    :intent/domain-tag  "PRF_CHAIN_CONFIGURATION_TRANSITION_V1"
    :intent/description "Canonical SHA-256 identity of a chain-configuration-transition.v1 governance transition"
    :intent/includes    #{:transition/schema :protocol/genesis-root :target
                          :configuration/parent-root :configuration/new-root
                          :verifier-registry/root :epoch}
    :intent/excludes    #{:runtime-values :functions :deployment-metadata
                          :block-context :timestamps}
    :intent/projection-fn project-chain-configuration-transition
    :intent/version     1}})

(defn resolve-intent
  "Look up an intent contract by keyword name from the registry.
   Returns the full intent contract map or throws on unknown intent.
   Used internally by hash-with-intent and available for external
   inspection and linting."
  [intent-kw]
  (or (hash-intents intent-kw)
      (throw (ex-info "Unknown hash intent"
                      {:intent intent-kw
                       :known  (vec (keys hash-intents))}))))

(defn- validate-prefix-free-domain-tags!
  "Fail closed when the domain-tag set violates the consecutive-concatenation
   framing requirement: no tag may be a strict prefix of another.  Because
   domain-hash concatenates DOMAIN_TAG || CANONICAL_BYTES without a length
   frame, a prefix relationship is the only way two distinct (tag, value)
   pairs can collide on the concatenated byte stream.  Returns nil when
   prefix-free, throws ex-info otherwise."
  [tags]
  (doseq [t tags]
    (doseq [t2 tags]
      (when (and (not= t t2)
                 (or (and (< (count t) (count t2)) (.startsWith t2 t))
                     (and (< (count t2) (count t)) (.startsWith t t2))))
        (throw (ex-info "Domain tags must be prefix-free: one domain tag is a strict prefix of another"
                        {:shorter (if (< (count t) (count t2)) t t2)
                         :longer  (if (< (count t) (count t2)) t2 t)
                         :guidance "domain-hash concatenates DOMAIN_TAG || CANONICAL_BYTES without a length frame; a prefix relationship makes the boundary ambiguous"}))))))

(defn validate-registry!
  "Validate the intent registry against INTENT_REGISTRY_SPEC_V1.
   Checks that every contract has all required fields with correct types,
   unique domain tags, and projection functions that return canonical-safe data.
   Returns nil if valid, throws on first violation.
   Call at startup or in test fixtures to ensure registry integrity."
  []
  (let [expected-fields [:intent/name :intent/domain-tag :intent/description
                         :intent/includes :intent/excludes
                         :intent/projection-fn :intent/version]
        field-types {:intent/name         keyword?
                     :intent/domain-tag   string?
                     :intent/description  string?
                     :intent/includes     set?
                     :intent/excludes     set?
                     :intent/projection-fn fn?
                     :intent/version      (every-pred integer? pos?)}]
    (doseq [[kw contract] hash-intents]
      (doseq [f expected-fields]
        (when-not (contains? contract f)
          (throw (ex-info (str "Intent " kw " missing required field " f)
                          {:intent kw :missing f}))))
      (doseq [[f pred] field-types]
        (when-not (pred (get contract f))
          (throw (ex-info (str "Intent " kw " field " f " has wrong type")
                          {:intent kw :field f :value (get contract f)}))))
      (when-not (= kw (:intent/name contract))
        (throw (ex-info "Intent registry key must match :intent/name"
                        {:intent kw :intent/name (:intent/name contract)})))
      (let [validation-samples
            {:action "test-action"
             :action-at {:action-hash "test-hash" :step 1 :block-time 100}
             :with-bounty-effect-set-v1 ["plan-root" ["effect-root-1" "effect-root-2"]]}
            sample (get validation-samples kw {:sample [:a :b] :n 1})
            projection-a ((:intent/projection-fn contract) sample kw)
            projection-b ((:intent/projection-fn contract) sample kw)]
        (try
          (validate-canonical-value! projection-a)
          (catch Exception e
            (throw (ex-info "Intent projection must produce canonical-safe data"
                            {:intent kw
                             :projection projection-a
                             :cause (.getMessage e)}
                            e))))
        (when (not= projection-a projection-b)
          (throw (ex-info "Intent projection must be deterministic"
                          {:intent kw
                           :projection-a projection-a
                           :projection-b projection-b})))
        (when-not ((set (vals domain-tags)) (:intent/domain-tag contract))
          (throw (ex-info "Intent domain tag must be registered in domain-tags"
                          {:intent kw
                           :domain-tag (:intent/domain-tag contract)})))
        ;; Self-hash exclusion cross-check (HASH_INTENT_REGISTRY_SPEC_V1 §2.6):
        ;; every self-hash key declared in :intent/excludes MUST be absent from
        ;; the projection output, so the exclusion is structural, not merely
        ;; declarative — this catches drift even though validate-intent-constraints!
        ;; is off in production.
        (let [excluded-self-hashes (keep #(when (self-hash-keys %) %)
                                         (:intent/excludes contract))]
          (when (seq excluded-self-hashes)
            (let [base (if (map? sample) sample {:sample [:a :b] :n 1})
                  base (if (= kw :action) (assoc base :action/type "test-action") base)
                  self-hash-sample (reduce (fn [m k] (assoc m k "self-hash-sentinel"))
                                           base excluded-self-hashes)
                  projected-self-hash ((:intent/projection-fn contract)
                                       self-hash-sample kw)]
              (doseq [k excluded-self-hashes]
                (when (contains? projected-self-hash k)
                  (throw (ex-info "Intent declares a self-hash exclusion its projection does not implement"
                                  {:intent kw
                                   :self-hash-key k
                                   :guidance "per HASH_INTENT_REGISTRY_SPEC_V1 §2.6 the exclusion must be structural (select-keys / strip-self-hash-fields), not declarative"})))))))))
    (let [tag->intents (reduce-kv (fn [acc kw contract]
                                    (update acc (:intent/domain-tag contract) (fnil conj []) kw))
                                  {}
                                  hash-intents)]
      (doseq [[tag intents] tag->intents]
        (when (< 1 (count intents))
          (throw (ex-info "Intent domain tags must be unique"
                          {:domain-tag tag :intents intents})))))
    ;; Consecutive-concatenation framing requirement (CANONICAL_HASH_SPEC_V1 §2):
    ;; domain-hash is SHA256(DOMAIN_TAG || CANONICAL_BYTES) with no length frame
    ;; on the tag.  For that concatenation to be byte-unambiguous across domains,
    ;; no domain tag may be a strict prefix of another — a prefix relationship is
    ;; the ONLY way two distinct (tag, canonical-bytes) pairs can produce the same
    ;; concatenated stream.  This is enforced here so a future tag cannot silently
    ;; introduce cross-domain collision ambiguity.
    (validate-prefix-free-domain-tags! (set (vals domain-tags)))
    nil))

(def ^:private registry-startup-validation
  "Forces intent registry validation when this namespace is loaded."
  (validate-registry!))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Constraint Enforcement
;; ──────────────────────────────────────────────────────────────────────────────
;; Self-validating identity graph: each intent contract enforces its
;; exclusion rules at test/development time, preventing:
;;   - Accidental misuse (hashing excluded data with wrong intent)
;;   - Silent semantic drift (intent contract drifts from actual usage)
;;   - Cross-intent hash comparison (comparing hashes from different intents)

(def ^:dynamic *validate-intent-constraints*
  "When truthy, hash-with-intent validates data against the intent's
   :intent/excludes before hashing. Enable in tests or development.
   Default false for production performance.

   Usage:
     (binding [hc/*validate-intent-constraints* true]
       (hc/hash-with-intent {:hash/intent :evidence-record} data))"
  false)

(defn- walk-for-excludes
  "Walk a value tree and return all nodes that match any of the given
   type-based exclude predicates. Each predicate takes a value and
   returns a violation string or nil."
  [predicates value]
  (let [results (volatile! [])
        preds (vec predicates)]
    (letfn [(walk [x]
              (doseq [[i pred] (map-indexed vector preds)
                      :let [r (pred x)]
                      :when r]
                (vswap! results conj {:predicate-index i :detail r}))
              (cond
                (instance? clojure.lang.IPersistentMap x)
                (run! (fn [[_ v]] (walk v)) x)
                (instance? clojure.lang.IPersistentVector x)
                (run! walk x)
                (instance? clojure.lang.IPersistentSet x)
                (run! walk x)
                (sequential? x)
                (run! walk x)))]
      (walk value)
      @results)))

(def ^:private exclude-type-checkers
  "Map from exclude category keywords to type-checker predicates.
   Each predicate returns a violation string or nil.
   Applied to every node in the data tree."
  {:functions (fn [v] (when (fn? v) "function value"))
   :sets      (fn [v] (when (instance? clojure.lang.IPersistentSet v)
                        "set (unsupported)"))
   :ratios    (fn [v] (when (instance? clojure.lang.Ratio v)
                        "ratio (unsupported)"))
   :instants  (fn [v] (when (instance? java.time.Instant v)
                        "java.time.Instant"))
   :doubles   (fn [v] (when (or (instance? Double v)
                                (instance? Float v))
                        "double or float (unsupported)"))
   :keywords  (fn [v] (when (instance? clojure.lang.Keyword v)
                        "keyword value (projected away by :evidence-content)"))})

(def ^:private exclude-root-checkers
  "Map from exclude category keywords to root-level checkers.
   Each predicate takes the ROOT value and returns a violation
   string or nil. These check structural properties."
  {:evidence-hash  (fn [v] (when (and (map? v) (contains? v :evidence/hash))
                             "root map contains :evidence/hash"))
   :timestamp      (fn [v] (when (and (map? v) (contains? v :evidence/timestamp))
                             "root map contains :evidence/timestamp"))
   :timestamps     (fn [v] (when (and (map? v) (contains? v :evidence/timestamp))
                             "root map contains :evidence/timestamp"))
   :hash-fields    (fn [v]
                     (when (and (map? v)
                                (some self-hash-keys (keys v)))
                       "map contains a self-hash key at root"))
   :chain-metadata (fn [v]
                     (when (and (map? v)
                                (some #(re-find #"^evidence/chain-" (name %))
                                      (keys v)))
                       "root map contains chain-metadata keys"))})

(defn validate-intent-constraints!
  "Validate that a value does not violate an intent contract's exclusion rules.
   Walks the value tree checking for excluded types and structural patterns.

   Throws ex-info with :violations detailing every detected violation.
   Returns nil if the value passes all checks.

   Runtime/type exclusions use walk-for-excludes (recursive tree walk).
   Structural/root exclusions use exclude-root-checkers (top-level only).
   Unknown exclude categories are silently skipped (they may require
   semantic analysis not expressible as type/structural checks).

   Callers can invoke this directly for defensive checking:
     (hc/validate-intent-constraints! :evidence-record data)"
  [intent-kw value]
  (let [contract (resolve-intent intent-kw)
        excludes (:intent/excludes contract)
        violations (volatile! [])]
    ;; Tree-walk type checks
    (let [type-preds (keep #(when-let [c (get exclude-type-checkers %)]
                              c)
                           excludes)
          type-violations (walk-for-excludes (vec type-preds) value)
          root-checkers (keep (fn [cat]
                                (when-let [c (get exclude-root-checkers cat)]
                                  [cat c]))
                              excludes)
          root-violations (into []
                                (keep (fn [[cat checker]]
                                        (when-let [msg (checker value)]
                                          {:category cat :detail msg}))
                                      root-checkers))
          all-violations (concat type-violations root-violations)]
      (when (seq all-violations)
        (throw (ex-info "Intent constraint violation"
                        {:intent intent-kw
                         :excludes (vec excludes)
                         :violations (vec all-violations)})))
      nil)))

(defn intent-hash=
  "Compare hash values with intent awareness.
   Prevents accidental cross-intent hash comparison.

   Each argument can be:
   - A map with :hash/intent and :hash/hex keys (intent-aware)
   - A string (legacy plain hex hash, intent-agnostic)

   When both arguments have intent metadata and intents differ,
   returns false. Use :allow-cross-intent? true to override.

   Usage:
     (intent-hash= result1 result2)
     (intent-hash= result1 result2 {:allow-cross-intent? true})"
  ([a b] (intent-hash= a b nil))
  ([a b {:keys [allow-cross-intent?] :or {allow-cross-intent? false}}]
   (let [a-intent (when (map? a) (:hash/intent a))
         b-intent (when (map? b) (:hash/intent b))
         a-hex    (if (map? a) (:hash/hex a) a)
         b-hex    (if (map? b) (:hash/hex b) b)]
     (if (and a-intent b-intent (not= a-intent b-intent) (not allow-cross-intent?))
       false
       (= a-hex b-hex)))))

(defn hash-with-intent
  "Compute a hash with an explicit intent declaration.

   The intent map documents WHY this hash is being computed, what
   projection (if any) is applied to the data, and what domain tag
   separates the hash. This prevents accidental misuse, silent
   semantic drift, and confusion during refactors.

   When *validate-intent-constraints* is true, validates data against
   the intent's :intent/excludes before hashing (enable in tests).

   Usage:
     (hash-with-intent {:hash/intent :world-structure} world-state)
     (hash-with-intent {:hash/intent :evidence-record} evidence-data)
     (hash-with-intent {:hash/intent :evidence-content} evidence-map)
     (hash-with-intent {:hash/intent :manifest} manifest-data)

   Returns a hex string (64 chars). For intent-aware comparison,
   use intent-hash= or wrap the result:
     {:hash/intent :evidence-record, :hash/hex (hash-with-intent ...)}

   See hash-intents for all supported intents with their scope
   and exclusion contracts."
  [{:keys [hash/intent]} value]
  (let [{:intent/keys [projection-fn domain-tag]} (resolve-intent intent)
        flattened-fields (atom [])
        projected (if (or (= projection-fn project-world-to-structure-view)
                          (= projection-fn project-for-content-hash))
                    (projection-fn value intent flattened-fields)
                    (projection-fn value intent))]
    (when *validate-intent-constraints*
      (validate-intent-constraints! intent value))
    (domain-hash domain-tag
                 (if (= projection-fn project-world-to-structure-view)
                   (dissoc projected :projection/flattened-fields)
                   projected))))