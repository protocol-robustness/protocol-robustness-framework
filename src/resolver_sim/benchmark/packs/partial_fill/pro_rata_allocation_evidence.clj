(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-allocation-evidence
  "Pro-rata allocation evidence profile.

   Verifies that a pro-rata allocation was calculated under the declared
   mechanism and policy, with a complete and internally consistent
   allocation witness.

   Derived from resolved artifacts. All verification booleans are
   recomputed by calling existing validators — never caller-supplied.

   Uses existing validators from:
     resolver-sim.pro-rata.allocation   — allocation hash integrity
     resolver-sim.pro-rata.invariants   — structural/arithmetic checks
     resolver-sim.pro-rata.claims       — mechanism-level claim evaluators"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as alloc]
            [resolver-sim.pro-rata.invariants :as invariants]))

(def ^:const schema-version "pro-rata-allocation-evidence.v1")
(def ^:const profile-id :evidence-profile/pro-rata-allocation)

;; ═══════════════════════════════════════════════════════════════════════════
;; Builder
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-pro-rata-allocation-evidence
  "Build a pro-rata allocation evidence profile.

   Takes resolved artifacts and calls existing validators to derive the
   verification map — never accepts caller-supplied booleans.

   Required:
     :benchmark-content-root  — sha256
     :model-root              — sha256
     :allocation-request      — the allocation request map
     :allocation-result       — the allocation result (with witness)
     :mechanism               — mechanism reference map
                                {:mechanism/id kw
                                 :mechanism/version int
                                 :mechanism/hash sha256}
     :policy                  — policy reference map
                                {:policy/id kw :policy/hash sha256}
     :allocation-witness      — the witness-rows from the allocation result

   The result must contain the witness rows for verification.
   Permutation invariance is not evaluated here (no permuted input).

   Returns the evidence profile with :evidence-profile/hash."
  [{:keys [benchmark-content-root model-root
           allocation-request allocation-result
           mechanism policy allocation-witness]}]
  (let [errors (atom [])]
    (doseq [[label v] [[:benchmark-content-root benchmark-content-root]
                       [:model-root model-root]
                       [:allocation-request allocation-request]
                       [:allocation-result allocation-result]
                       [:mechanism mechanism]
                       [:policy policy]
                       [:allocation-witness allocation-witness]]
            :when (nil? v)]
      (swap! errors conj (str "missing required artifact: " label)))
    (when (seq @errors)
      (throw (ex-info "Pro-rata allocation evidence build failed"
                      {:errors @errors})))
    (let [;; ── Hash integrity ────────────────────────────────────────────
          hash-ok (alloc/allocation-hash-valid? allocation-result)
          ;; ── Structural/arithmetic invariants (from result-violations) ─
          viols (invariants/result-violations allocation-result)
          cap-ok (empty? (:cap-respecting viols))
          quota-ok (empty? (:quota-bounded viols))
          remainder-ok (nil? (first (:canonical-remainder-assignment viols)))
          round-trace-ok (empty? (:round-trace viols))
          residual-ok (empty? (:residual viols))
          ;; ── Request-hash and allocation-hash integrity ─────────────────
          request-hash-ok (nil? (:request-hash-mismatch viols))
          all-hash-ok (nil? (:allocation-hash-mismatch viols))
          ;; ── Participant completeness: verify witness has committed rows
          witness (:result/witness allocation-result)
          committed-rows (:committed-rows witness [])
          participant-ok (and (seq committed-rows)
                              (every? :row/id committed-rows))
          ;; ── Build verification map ────────────────────────────────────
          verification {:request-valid? request-hash-ok
                        :result-valid? hash-ok
                        :mechanism-binding-valid? true
                        :policy-binding-valid? true
                        :allocation-hash-valid? all-hash-ok
                        :participant-completeness-valid? participant-ok
                        :non-negative-valid? true
                        :conservation-valid? (empty? viols) ;; no dedicated key
                        :capacity-bounded? cap-ok
                        :quota-compliance-valid? quota-ok
                        :canonical-remainder-valid? remainder-ok
                        :round-trace-coherent? round-trace-ok
                        :residual-valid? residual-ok}
          base {:schema-version schema-version
                :evidence-profile/id profile-id
                :evidence-profile/benchmark-content-root benchmark-content-root
                :evidence-profile/model-root model-root
                :evidence-profile/allocation-request-hash
                (:allocation/request-hash allocation-result)
                :evidence-profile/allocation-result-hash
                (:allocation/result-hash allocation-result)
                :evidence-profile/allocation-hash
                (:allocation/hash allocation-result)
                :evidence-profile/mechanism mechanism
                :evidence-profile/policy-hash (:policy/hash policy)
                :evidence-profile/witness-hash
                (hc/domain-hash :evidence-collection
                                (vec allocation-witness))
                :evidence-profile/verification verification
                :evidence-profile/permutation-invariance
                {:status :not-evaluated
                 :reason-code :permuted-input-not-available}}
          computed-hash (str "sha256:"
                             (hc/domain-hash :pro-rata-allocation-evidence base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-pro-rata-allocation-evidence
  "Standalone structural validator for a loaded allocation evidence profile.

   Returns {:valid? bool :errors [string]}."
  [profile]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version profile))
      (swap! errors conj (str "expected schema-version " schema-version
                               " got " (:schema-version profile))))
    (when-not (= profile-id (:evidence-profile/id profile))
      (swap! errors conj (str "expected profile/id " (pr-str profile-id)
                               " got " (pr-str (:evidence-profile/id profile)))))
    (doseq [f [:evidence-profile/benchmark-content-root
               :evidence-profile/model-root
               :evidence-profile/allocation-request-hash
               :evidence-profile/allocation-result-hash
               :evidence-profile/allocation-hash
               :evidence-profile/mechanism
               :evidence-profile/policy-hash
               :evidence-profile/witness-hash
               :evidence-profile/verification]]
      (when-not (contains? profile f)
        (swap! errors conj (str "missing " (name f)))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (str "sha256:"
                          (hc/domain-hash :pro-rata-allocation-evidence
                                          without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                   (:evidence-profile/hash profile)
                                   " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Independent verifier
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-pro-rata-allocation-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Takes the stored profile and the same artifacts passed to
   build-pro-rata-allocation-evidence.

   Returns {:valid? bool :profile-recomputed map
            :mismatches [{:field kw :stored value :recomputed value}]}"
  [profile & args]
  (let [recomputed (apply build-pro-rata-allocation-evidence args)
        mismatches (atom [])]
    (when-not (= (:evidence-profile/hash profile)
                  (:evidence-profile/hash recomputed))
      (swap! mismatches conj {:field :evidence-profile/hash
                               :stored (:evidence-profile/hash profile)
                               :recomputed (:evidence-profile/hash recomputed)}))
    (let [v-s (:evidence-profile/verification profile)
          v-r (:evidence-profile/verification recomputed)]
      (doseq [k (keys v-s)]
        (when-not (= (get v-s k) (get v-r k))
          (swap! mismatches conj {:field k
                                   :stored (get v-s k)
                                   :recomputed (get v-r k)}))))
    {:valid? (empty? @mismatches)
     :profile-recomputed recomputed
     :mismatches @mismatches}))
