(ns resolver-sim.allocation.certificate
  "Allocation assurance certificate composition.

   Implements `allocation-assurance-certificate.v1`. In this phase the
   certificate records subject roots, the selected outcome, the result root,
   ordered assertion results, per-assertion assurance classifications, the
   exact-replication classification, PRF artifact identity, and proof fields
   explicitly marked as phase stubs.

   Assurance classifications used in this phase:
     :zk-proof             — not yet available; never assigned in this phase
     :independent-replay    — kernel arithmetic assertions reproducible by the
                              independent Rust kernel
     :economic-assumption   — :assume-punishment-credible remains an explicit
                              economic assumption
     :not-yet-evaluated     — proof-backed fields

   Exact-replication classification is bound to native Rust evidence
   (resolver-sim.allocation.native-evidence): it is proof-backed ONLY when the
   pinned native Rust implementation was actually executed and compared against
   the reference under the exact-replication contract. A mock result, or
   evidence bound to another result or another pinned implementation, never
   produces a stronger classification than the evidence evaluated.

   No assertion is falsely classified as :zk-proof in this phase."
  (:require [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.native-evidence :as native-evidence]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.hash.canonical :as hc]))

(def schema-version "allocation-assurance-certificate.v1")

(defn assertion-assurance
  "Assurance classification for a kernel assertion id."
  [assertion-id]
  (case assertion-id
    (:allocation.assertion/claimant-set-root-valid
     :allocation.assertion/outcome-set-root-valid
     :allocation.assertion/proposed-rates-root-valid
     :allocation.assertion/rates-canonical-exact
     :allocation.assertion/rates-sum-to-one
     :allocation.assertion/outcomes-eligible-only
     :allocation.assertion/outcomes-no-duplicate-claims
     :allocation.assertion/outcomes-all-or-nothing
     :allocation.assertion/outcomes-exact-capacity
     :allocation.assertion/proportional-proposed
     :allocation.assertion/randomness-selection-valid
     :allocation.assertion/selected-outcome-membership
     :allocation.assertion/result-root-valid
     :allocation.assertion/result-capacity-reconciles)
    :independent-replay
    :not-yet-evaluated))

(defn- prf-artifact-identity
  "PRF artifact identity for the certificate."
  []
  {:artifact-kind :prf-allocation-kernel
   :kernel-version context/kernel-version
   :schema-version context/schema-version
   :canonical-abi-version "CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI"})

(defn exact-replication-classification
  "Classify the exact-replication scope from native Rust evidence bound to the
   reference result.

   native-evidence: nil, or a native-evidence map (native-evidence.v1).
   kernel-result:   the kernel public values; carries an optional
                    :native/reference block with the committed results-artifact
                    hash and pinned Rust identity.

   Returns {:classification kw :proof-backed? bool :reason kw :evidence {...}}.
   Proof-backed (:native-exact-match) only when native evidence was actually
   executed and matched under the exact-replication contract."
  [native-evidence kernel-result]
  (native-evidence/exact-replication-classification
   native-evidence
   {:results-artifact-hash (get-in kernel-result [:native/reference :results-artifact-hash])
    :input-root (:allocation-context-hash kernel-result)
    :result-root (:result-root kernel-result)
    :pinned-prf (prf-artifact-identity)
    :pinned-rust (get-in kernel-result [:native/reference :pinned-rust])}))

(defn- proof-block
  "Compose the proof block. Status is :valid only when the exact-replication
   classification is proof-backed; a mock result never produces a valid proof."
  [classification kernel-result]
  (let [proof-backed? (:proof-backed? classification)
        evidence (:evidence classification)
        evidence-source (some-> evidence :native-evidence/source)
        status (:result/status kernel-result)]
    {:status (if proof-backed? :valid :not-yet-evaluated)
     :proof-hash (when proof-backed?
                   (hc/domain-hash "NATIVE_EXACT_REPLICATION_V1"
                                   (dissoc evidence
                                           :native-evidence/status
                                           :native-evidence/reason)))
     :proof-mode (cond
                   (= :mock evidence-source) :mock-native
                   proof-backed? :native-rust
                   :else :mock-native)
     :native-evidence evidence
     :public-values-hash (when (= :passing status)
                           (hc/domain-hash :certificate-assertions
                                           {:schema-version schema-version
                                            :subject (:certificate-assertions-digest kernel-result)}))}))

(defn compose-certificate
  "Compose an allocation-assurance-certificate.v1 from a kernel result.

   The kernel result must be the stable public-value map returned by
   `kernel/run-kernel`. If the result is rejected, the certificate records the
   rejection classification and proof fields remain :not-yet-evaluated.

   When a coprocessor `round-state` token is supplied (second arity), the
   certificate additionally carries a `:round-lifecycle` block with the
   `cancellation-window-assertion` for the canonical probabilistic-allocation
   lifecycle. The lifecycle assertion is projected separately from the kernel
   assertions; it never claims :zk-proof in this phase.

   When native Rust evidence is supplied (third arity), the exact-replication
   classification and proof block are bound to that evidence (see
   resolver-sim.allocation.native-evidence). Without it, exact-replication
   stays :pending-independent-replay and the proof block stays
   :not-yet-evaluated."
  ([kernel-result] (compose-certificate kernel-result nil nil))
  ([kernel-result round-state-token] (compose-certificate kernel-result round-state-token nil))
  ([kernel-result round-state-token native-evidence]
   (let [assertions (:assertions kernel-result [])
         status (:result/status kernel-result)
         exact-replication (exact-replication-classification native-evidence kernel-result)
         base
         {:schema-version schema-version
          :subject-roots {:allocation-context-hash (:allocation-context-hash kernel-result)
                          :claimant-set-root (:claimant-set-root kernel-result)
                          :outcome-set-root (:outcome-set-root kernel-result)
                          :proposed-rates-root (:proposed-rates-root kernel-result)
                          :rate-derived-summary-hash (:rate-derived-summary-hash kernel-result)}
          :selected-outcome {:selected-outcome-id (:selected-outcome-id kernel-result)
                             :selected-outcome-index (:selected-outcome-index kernel-result)
                             :selected-outcome-hash (:selected-outcome-hash kernel-result)}
          :result-root (:result-root kernel-result)
          :result-totals {:total-allocated (:total-allocated kernel-result)
                          :residual-capacity (:residual-capacity kernel-result)}
          :assertions
          (mapv (fn [{:keys [assertion/id assertion/result]}]
                  {:assertion/id id
                   :assertion/result result
                   :assurance (assertion-assurance id)})
                assertions)
          :exact-replication exact-replication
          :prf-artifact (prf-artifact-identity)
          :assume-punishment-credible
          {:status :declared-supported
           :assurance :economic-assumption
           :profile-hash nil}
          :proof (proof-block exact-replication kernel-result)
          :result/status status
          :rejection/classification (:rejection/classification kernel-result)
          :rejection/reason (:rejection/reason kernel-result)}
         lifecycle
         (when (some? round-state-token)
           {:round-state (round-state/lifecycle-target-state round-state-token)
            :cancellation/assertion
            (round-state/cancellation-assertion
             {:profile-id "alloc/2-3"} round-state-token)})]
     (if (nil? lifecycle)
       base
       (assoc base :round-lifecycle lifecycle)))))
