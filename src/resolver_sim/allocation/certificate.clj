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

   No assertion is falsely classified as :zk-proof in this phase."
  (:require [resolver-sim.allocation.context :as context]
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

(defn exact-replication-classification
  "Classification of the PRF-versus-native comparison scope. In this phase the
   kernel runs as a single reference path; the classification is recorded as
   pending-independent-replay until the native Rust gate is attached."
  []
  :pending-independent-replay)

(defn- prf-artifact-identity
  "PRF artifact identity for the certificate."
  []
  {:artifact-kind :prf-allocation-kernel
   :kernel-version context/kernel-version
   :schema-version context/schema-version
   :canonical-abi-version "CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI"})

(defn compose-certificate
  "Compose an allocation-assurance-certificate.v1 from a kernel result.

   The kernel result must be the stable public-value map returned by
   `kernel/run-kernel`. If the result is rejected, the certificate records the
   rejection classification and proof fields remain :not-yet-evaluated."
  [kernel-result]
  (let [assertions (:assertions kernel-result [])
        status (:result/status kernel-result)]
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
     :exact-replication (exact-replication-classification)
     :prf-artifact (prf-artifact-identity)
     :assume-punishment-credible
     {:status :declared-supported
      :assurance :economic-assumption
      :profile-hash nil}
     :proof
     {:status :not-yet-evaluated
      :proof-hash nil
      :proof-mode :mock-native
      :public-values-hash (when (= status :passing)
                            (hc/domain-hash :certificate-assertions
                                            {:schema-version schema-version
                                             :subject (:certificate-assertions-digest kernel-result)}))}
     :result/status status
     :rejection/classification (:rejection/classification kernel-result)
     :rejection/reason (:rejection/reason kernel-result)}))
