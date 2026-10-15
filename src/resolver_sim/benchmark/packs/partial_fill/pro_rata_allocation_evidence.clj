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
            [resolver-sim.pro-rata.invariants :as invariants]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version-v1 "pro-rata-allocation-evidence.v1")
(def ^:const schema-version "pro-rata-allocation-evidence.v2")
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

   Returns a v2 evidence profile. Arithmetic findings are recomputed from the
   supplied canonical request/result; references whose source bodies are not
   supplied are explicitly classified as unverified metadata."
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
    (let [request-equal? (= allocation-request (:canonical-request allocation-result))
          request-hash-ok (and request-equal?
                               (= (:request/hash allocation-result)
                                  (hc/hash-with-intent {:hash/intent :projection-artifact}
                                                       (:canonical-request allocation-result))))
          result-hash-ok (alloc/allocation-hash-valid? allocation-result)
          witness (:result/witness allocation-result)
          committed-rows (vec (or (:committed-rows witness)
                                  (:rows allocation-result)
                                  []))
          witness-equal? (= (vec allocation-witness) committed-rows)
          rows (vec (:rows allocation-result []))
          row-ids (map :row/id rows)
          participant-ok (and (seq committed-rows)
                              (every? :row/id committed-rows)
                              (= (count row-ids) (count (set row-ids))))
          ;; Use the public aggregate validator. The older evidence builder
          ;; treated several private validator results as maps, so nil lookups
          ;; made failures appear to pass.
          all-violations (invariants/result-violations allocation-result)
          reason-set (fn [reasons]
                       (filterv #(contains? reasons (:reason %)) all-violations))
          cap-violations (reason-set #{:pro-rata/invalid-allocation
                                       :pro-rata/cap-exceeded
                                       :pro-rata/request-exceeded})
          quota-violations (reason-set #{:pro-rata/quota-bounded-failed})
          residual-violations (reason-set #{:pro-rata/invalid-unallocated-residual
                                            :pro-rata/unexpected-residual-reason
                                            :pro-rata/residual-reason-not-established
                                            :pro-rata/unsupported-residual-reason})
          conservation-violations (reason-set #{:pro-rata/aggregate-conservation-violated})
          round-trace-violations (reason-set #{:pro-rata/active-weight-mismatch
                                               :pro-rata/round-conservation-failed
                                               :pro-rata/cap-commitment-mismatch
                                               :pro-rata/round-continuity-failed
                                               :pro-rata/active-set-monotonicity-failed
                                               :pro-rata/final-round-binding-mismatch})
          remainder-violations (reason-set #{:pro-rata/remainder-rank-mismatch
                                             :pro-rata/remainder-award-mismatch
                                             :pro-rata/remainder-order-mismatch})
          remainder-applicable? (= :largest-remainder (:rounding-policy allocation-result))
          non-negative-ok? (every? (fn [row]
                                     (and (integer? (:allocated row))
                                          (not (neg? (:allocated row))))) rows)
          request-mechanism-version (:mechanism/version allocation-request)
          mechanism-binding-ok? (and (keyword? (:mechanism/id mechanism))
                                     (some? (:mechanism/hash mechanism))
                                     (= request-mechanism-version (:mechanism/version mechanism)))
          policy-parameters (:policy/parameters policy)
          policy-fields [:rounding-policy :tie-break-policy :redistribution-policy]
          policy-binding-ok? (and (map? policy-parameters)
                                  (every? #(contains? policy-parameters %) policy-fields)
                                  (every? #(= (get allocation-request %)
                                              (get policy-parameters %)) policy-fields))
          commitments-valid? (every? hash-ref/valid-sha256-ref?
                                     [benchmark-content-root model-root
                                      (:mechanism/hash mechanism) (:policy/hash policy)])
          check-statuses {:source-commitments (if commitments-valid? :pass :fail)
                          :request-binding (if request-hash-ok :pass :fail)
                          :result-integrity (if result-hash-ok :pass :fail)
                          :witness-binding (if witness-equal? :pass :fail)
                          :participant-completeness (if participant-ok :pass :fail)
                          :non-negative (if non-negative-ok? :pass :fail)
                          :capacity (if (empty? cap-violations) :pass :fail)
                          :quota (if (empty? quota-violations) :pass :fail)
                          :conservation (if (empty? conservation-violations) :pass :fail)
                          :residual (if (empty? residual-violations) :pass :fail)
                          :round-trace (if (empty? round-trace-violations) :pass :fail)
                          :remainder (cond
                                       (not remainder-applicable?) :not-applicable
                                       (empty? remainder-violations) :pass
                                       :else :fail)
                          :mechanism-reference (if mechanism-binding-ok? :pass :fail)
                          :policy-request-fields (if policy-binding-ok? :pass :fail)
                          :all-result-invariants (if (empty? all-violations) :pass :fail)}
          verification-status (if (some #{:fail} (vals check-statuses)) :invalid :verified)
          verification {:source-commitments-valid? commitments-valid?
                        :request-valid? request-hash-ok
                        :result-valid? result-hash-ok
                        :mechanism-binding-valid? mechanism-binding-ok?
                        :policy-binding-valid? policy-binding-ok?
                        :allocation-hash-valid? result-hash-ok
                        :witness-binding-valid? witness-equal?
                        :participant-completeness-valid? participant-ok
                        :non-negative-valid? non-negative-ok?
                        :conservation-valid? (empty? conservation-violations)
                        :capacity-bounded? (empty? cap-violations)
                        :quota-compliance-valid? (empty? quota-violations)
                        :canonical-remainder-valid? (when remainder-applicable?
                                                      (empty? remainder-violations))
                        :round-trace-coherent? (empty? round-trace-violations)
                        :residual-valid? (empty? residual-violations)}
          base {:schema-version schema-version
                :evidence-profile/id profile-id
                :evidence-profile/benchmark-content-root benchmark-content-root
                :evidence-profile/model-root model-root
                :evidence-profile/allocation-request-hash
                (hash-ref/sha256-ref (:request/hash allocation-result))
                :evidence-profile/allocation-result-hash
                (hash-ref/sha256-ref (:allocation/hash allocation-result))
                :evidence-profile/allocation-hash
                (hash-ref/sha256-ref (:allocation/hash allocation-result))
                :evidence-profile/mechanism mechanism
                :evidence-profile/policy-hash (:policy/hash policy)
                :evidence-profile/witness-hash
                (hash-ref/sha256-ref
                 (hc/domain-hash :evidence-collection committed-rows))
                :evidence-profile/verification-level :semantic
                :evidence-profile/verification-status verification-status
                :evidence-profile/verification-checks check-statuses
                :evidence-profile/unverified-references
                [:mechanism-content :policy-content]
                :evidence-profile/verification verification
                :evidence-profile/permutation-invariance
                {:status :not-evaluated
                 :reason-code :permuted-input-not-available}}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :pro-rata-allocation-evidence-v2 base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-pro-rata-allocation-evidence
  "Integrity-only validator for a loaded allocation evidence profile.

   A passing result establishes schema and self-hash integrity only. It does
   not establish that the allocation findings are true; use the independent
   verifier with the authoritative request/result/witness for that.
   Returns an explicitly qualified result map."
  [profile]
  (let [errors (atom [])]
    (when-not (contains? #{schema-version-v1 schema-version} (:schema-version profile))
      (swap! errors conj (str "unsupported schema-version " (:schema-version profile))))
    (when-not (= profile-id (:evidence-profile/id profile))
      (swap! errors conj (str "expected profile/id " (pr-str profile-id)
                              " got " (pr-str (:evidence-profile/id profile)))))
    (when (= schema-version (:schema-version profile))
      (let [checks (:evidence-profile/verification-checks profile)
            status (:evidence-profile/verification-status profile)
            values (when (map? checks) (vals checks))]
        (when-not (= :semantic (:evidence-profile/verification-level profile))
          (swap! errors conj "v2 profile must declare semantic verification level"))
        (when-not (map? checks)
          (swap! errors conj "missing verification-checks map"))
        (when (some #(not (contains? #{:pass :fail :not-run :not-applicable} %)) values)
          (swap! errors conj "verification-checks contains an unsupported status"))
        (when (and (= :verified status) (some #{:fail :not-run} values))
          (swap! errors conj "verified status conflicts with failed/unrun checks"))
        (when (and (= :invalid status) (not (some #{:fail} values)))
          (swap! errors conj "invalid status has no failed check"))
        (when (and (= :blocked status) (not (some #{:not-run} values)))
          (swap! errors conj "blocked status has no unrun check"))))
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
    (doseq [f [:evidence-profile/benchmark-content-root
               :evidence-profile/model-root
               :evidence-profile/allocation-request-hash
               :evidence-profile/allocation-result-hash
               :evidence-profile/allocation-hash
               :evidence-profile/policy-hash
               :evidence-profile/witness-hash
               :evidence-profile/hash]
            :when (not (hash-ref/valid-sha256-ref? (get profile f)))]
      (swap! errors conj (str "invalid canonical SHA-256 reference in " (name f))))
    (when-not (hash-ref/valid-sha256-ref? (get-in profile
                                                  [:evidence-profile/mechanism :mechanism/hash]))
      (swap! errors conj "invalid canonical SHA-256 reference in mechanism/hash"))
    (when-not (string? (:evidence-profile/hash profile))
      (swap! errors conj "missing evidence-profile/hash"))
    (when (string? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            domain (if (= schema-version-v1 (:schema-version profile))
                     :pro-rata-allocation-evidence
                     :pro-rata-allocation-evidence-v2)
            computed (hash-ref/sha256-ref
                      (hc/domain-hash domain
                                      without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors)
     :verification-level :integrity
     :status (if (empty? @errors) :integrity-verified :invalid)
     :semantic-status :not-run
     :errors @errors}))

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
  (if-not (= schema-version (:schema-version profile))
    {:valid? false :verification-level :semantic :status :blocked
     :semantic-status :not-run
     :errors [:legacy-profile-has-no-source-bound-semantic-verification]}
    (let [integrity (validate-pro-rata-allocation-evidence profile)
          recomputed (apply build-pro-rata-allocation-evidence args)
          mismatches (atom [])]
      (when-not (:valid? integrity)
        (swap! mismatches conj {:field :profile-integrity :errors (:errors integrity)}))
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
      {:valid? (and (empty? @mismatches)
                    (= :verified (:evidence-profile/verification-status recomputed)))
       :verification-level :semantic
       :status (cond
                 (seq @mismatches) :invalid
                 (= :verified (:evidence-profile/verification-status recomputed)) :verified
                 :else :invalid)
       :semantic-status (if (= :verified (:evidence-profile/verification-status recomputed))
                          :verified :invalid)
       :profile-recomputed recomputed
       :mismatches @mismatches})))
