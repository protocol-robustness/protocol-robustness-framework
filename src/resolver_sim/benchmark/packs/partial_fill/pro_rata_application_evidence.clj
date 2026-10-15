(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence
  "Pro-rata application evidence profile.

   Verifies that the allocation outcome was propagated, reconciled with
   accounting, and written into authoritative position state.

   Builds upon the existing six-level partial-fill evidence ladder
   rather than replacing it.

   Uses existing validators from:
     resolver-sim.yield.partial-fill          — propagation/allocation binding
     resolver-sim.yield.invariants             — accounting reconciliation
     resolver-sim.benchmark.packs.partial-fill.evidence — state write-back,
                                                         continuity, ladder"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.invariants :as yield-inv]

            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version-v1 "pro-rata-application-evidence.v1")
(def ^:const schema-version "pro-rata-application-evidence.v2")
(def ^:const profile-id :evidence-profile/pro-rata-application)

(defn- recompute-write-back
  "Recompute the write-back facts from the supplied projection and final world.
   The supplied `:verified?` flags are deliberately ignored."
  [write-back world-after]
  (mapv (fn [entry]
          (let [participant-id (:participant/id entry)
                token (:token entry)
                withdrawn (:withdrawn entry)
                final-withdrawn (get-in world-after [:yield/withdrawn token participant-id] 0)
                final-position (get-in world-after [:yield/positions participant-id])
                final-position-hash (when final-position
                                      (hash-ref/sha256-ref
                                       (hc/domain-hash :state-projection final-position)))
                final-deferred (get-in final-position
                                       (if (contains? final-position :position/deferred-position)
                                         [:position/deferred-position :position/current-amount]
                                         [:deferred-position :position/current-amount]))
                before-amount (:before withdrawn)
                delta-amount (:delta withdrawn)
                after-amount (:after withdrawn)
                withdrawn-ok? (and (integer? before-amount)
                                   (integer? delta-amount)
                                   (integer? after-amount)
                                   (= (- before-amount delta-amount) after-amount)
                                   (= final-withdrawn (:after withdrawn)))
                position-ok? (and (some? final-position-hash)
                                  (= final-position-hash
                                     (get-in entry [:position :after-hash])))
                deferred-ok? (and (some? final-position)
                                  (= final-deferred
                                     (get-in entry [:deferred-position
                                                    :successor-current-amount])))]
            (assoc entry
                   :withdrawn (assoc withdrawn :final-world-value final-withdrawn
                                               :verified? withdrawn-ok?)
                   :position (assoc (:position entry)
                                    :final-world-position-hash final-position-hash
                                    :verified? position-ok?)
                   :deferred-position (assoc (:deferred-position entry)
                                             :final-world-current-amount final-deferred
                                             :verified? deferred-ok?)
                   :verified? (and withdrawn-ok? position-ok? deferred-ok?))))
        (or write-back [])))

(defn- continuity-status
  "Classify continuity check results.
   Returns {:status :not-verifiable | :not-observed
            :reason-code kw :detail str}"
  [continuity-evidence]
  (if (empty? continuity-evidence)
    {:status :not-observed
     :reason-code :no-next-transition
     :detail "terminal scenario — no later transition consumes this state"}
    {:status :not-verifiable
     :reason-code :successor-state-source-not-supplied
     :detail "submitted continuity flags are not independently recomputed here"}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Builder
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-pro-rata-application-evidence
  "Build a pro-rata application evidence profile.

   Required:
     :allocation-evidence-hash     — sha256 of the allocation evidence profile
     :propagation                  — resolved propagation artifact
     :application                  — resolved application artifact
     :world-before                 — world state before application
     :world-after                  — world state after application
     :state-write-back-evidence    — from derive-state-write-back
     :continuity-evidence          — from derive-continuity-evidence
     :evidence-ladder              — from application-evidence-ladder
     :operational-outcome          — from evaluate-operational

   Returns a v2 profile. Inputs whose underlying source facts are not supplied
   are reported as not-run/blocked rather than inherited from caller flags."
  [{:keys [allocation-evidence-hash allocation-decision propagation application
           world-before world-after
           state-write-back-evidence continuity-evidence
           evidence-ladder operational-outcome]}]
  (let [errors (atom [])]
    (doseq [[label v] [[:allocation-evidence-hash allocation-evidence-hash]
                       [:propagation propagation]
                       [:application application]
                       [:world-before world-before]
                       [:world-after world-after]
                       [:state-write-back-evidence state-write-back-evidence]
                       [:continuity-evidence continuity-evidence]
                       [:evidence-ladder evidence-ladder]
                       [:operational-outcome operational-outcome]]
            :when (nil? v)]
      (swap! errors conj (str "missing required artifact: " label)))
    (when (seq @errors)
      (throw (ex-info "Pro-rata application evidence build failed"
                      {:errors @errors})))
    (let [;; ── Propagation validation ────────────────────────────────────
          prop-validation (pf/validate-pro-rata-propagation propagation)
          prop-valid? (true? (:valid? prop-validation))
          ;; A propagation's self-hash does not establish that it translates
          ;; the exact source decision. Check that relationship when supplied.
          prop-binding (when allocation-decision
                         (pf/propagation-allocation-binding-violations
                          allocation-decision propagation))
          prop-binding-status (cond
                                (nil? prop-binding) :not-run
                                (seq prop-binding) :fail
                                :else :pass)
          ;; ── Accounting reconciliation ─────────────────────────────────
          acct-result (yield-inv/check-pro-rata-accounting-reconciles
                       world-after)
          acct-ok (:holds? acct-result)
          prop-complete (yield-inv/check-pro-rata-propagation-complete
                         world-after)
          prop-complete-ok (every? (fn [[_k v]] (or (true? v) (nil? v)))
                                   prop-complete)
          ;; ── State write-back ──────────────────────────────────────────
          recomputed-write-back (recompute-write-back state-write-back-evidence world-after)
          wb-ok (and (seq recomputed-write-back)
                     (every? (fn [wb]
                               (and (true? (:verified? wb))
                                    (true? (get-in wb [:withdrawn :verified?]))
                                    (true? (get-in wb [:position :verified?]))
                                    (true? (get-in wb [:deferred-position :verified?]))))
                             recomputed-write-back))
          ;; ── Deferred current-amount ───────────────────────────────────
          deferred-ca-ok (and (seq recomputed-write-back)
                              (every? #(true? (get-in % [:deferred-position :verified?]))
                                      recomputed-write-back))
          ;; ── Current-amount continuity ─────────────────────────────────
          ;; Successor continuity requires a successor-state source, which this
          ;; builder does not receive. Preserve the submitted observation, but
          ;; do not upgrade it to a verified fact here.
          amt-ok nil
          ;; ── Next precondition continuity ──────────────────────────────
          cont-classification (continuity-status continuity-evidence)
          ;; ── Apparent application vs accounting reconciliation ─────────
          app-accounting (get operational-outcome :authoritative-application)
          _accounting-only-ok (and (not= :fail app-accounting)
                                  ;; state write-back may fail while accounting passes
                                   (some? app-accounting))
          ;; ── Build verification map ────────────────────────────────────
          application-hash-valid? (and (string? (:application/hash application))
                                       (= (:application/hash application)
                                          (pf/application-hash application)))
          app-propagation-id (or (:propagation-id application)
                                 (get-in application [:propagation/reference :propagation/id]))
          app-propagation-hash (or (:propagation/hash application)
                                   (get-in application [:propagation/reference :propagation/hash]))
          application-binding-valid? (and (= (:propagation/id propagation) app-propagation-id)
                                          (= (:propagation/hash propagation) app-propagation-hash))
          accounting-entries-match? (= (pf/canonical-accounting-entries
                                        (:accounting-entries propagation []))
                                       (pf/canonical-accounting-entries
                                        (:accounting-entries application [])))
          allocation-binding-valid? (hash-ref/valid-sha256-ref? allocation-evidence-hash)
          check-statuses {:allocation-evidence-binding (if allocation-binding-valid? :pass :fail)
                          :propagation-integrity (if prop-valid? :pass :fail)
                          :propagation-allocation-binding prop-binding-status
                          :application-integrity (if application-hash-valid? :pass :fail)
                          :application-propagation-binding (if application-binding-valid? :pass :fail)
                          :application-accounting-binding (if accounting-entries-match? :pass :fail)
                          :accounting-reconciliation (if acct-ok :pass :fail)
                          :state-write-back (if wb-ok :pass :fail)
                          :deferred-position-write-back (if deferred-ca-ok :pass :fail)
                          :current-amount-continuity (if (empty? continuity-evidence)
                                                       :not-applicable :not-run)
                          :successor-continuity (if (empty? continuity-evidence)
                                                  :not-applicable :not-run)}
          verification-status (cond
                                (some #{:fail} (vals check-statuses)) :invalid
                                (some #{:not-run} (vals check-statuses)) :blocked
                                :else :verified)
          verification {:allocation-evidence-binding-valid? allocation-binding-valid?
                        :propagation-valid? prop-valid?
                        :propagation-allocation-binding-status prop-binding-status
                        :propagation-allocation-binding-violations prop-binding
                        :application-binding-valid? (and application-hash-valid?
                                                         application-binding-valid?
                                                         accounting-entries-match?)
                        :application-hash-valid? application-hash-valid?
                        :application-propagation-binding-valid? application-binding-valid?
                        :application-accounting-binding-valid? accounting-entries-match?
                        :apparent-application-recorded?
                        (some? (:apparent-application application))
                        :accounting-reconciled? acct-ok
                        :propagation-complete? prop-complete-ok
                        :authoritative-state-write-back-verified? wb-ok
                        :deferred-current-amount-verified? deferred-ca-ok
                        :current-amount-continuous? amt-ok
                        :next-precondition-continuity cont-classification}
          base {:schema-version schema-version
                :evidence-profile/id profile-id
                :evidence-profile/allocation-evidence-hash
                allocation-evidence-hash
                :evidence-profile/propagation-hash
                (:propagation/hash propagation)
                :evidence-profile/partial-fill-decision-root
                (when allocation-decision
                  (hash-ref/sha256-ref (:decision/hash allocation-decision)))
                :evidence-profile/application-hash
                (when application (hash-ref/sha256-ref (:application/hash application)))
                :evidence-profile/apparent-application-hash
                (when-let [aa (:apparent-application application)]
                  (hc/domain-hash :evidence-collection aa))
                :evidence-profile/accounting-evidence-hash
                (when-let [aes (:accounting-entries application)]
                  (hash-ref/sha256-ref
                   (hc/domain-hash
                    :evidence-collection
                    (pf/canonical-accounting-entries aes))))
                :evidence-profile/state-write-back-evidence-hash
                (when (seq state-write-back-evidence)
                  (hash-ref/sha256-ref
                   (hc/domain-hash :evidence-collection
                                   (vec state-write-back-evidence))))
                :evidence-profile/current-amount-evidence-hash
                (hash-ref/sha256-ref
                 (hc/domain-hash :evidence-collection
                                 (mapv :deferred-position recomputed-write-back)))
                :evidence-profile/continuity-evidence-hash
                (when (seq continuity-evidence)
                  (hash-ref/sha256-ref
                   (hc/domain-hash :evidence-collection
                                   (vec continuity-evidence))))
                :evidence-profile/verification-level :semantic
                :evidence-profile/verification-status verification-status
                :evidence-profile/verification-checks check-statuses
                :evidence-profile/unverified-evidence
                (cond-> []
                  (nil? allocation-decision) (conj :propagation-allocation-binding)
                  (seq continuity-evidence) (conj :current-amount-continuity
                                                  :successor-continuity))
                :evidence-profile/verification verification}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :pro-rata-application-evidence-v2
                                         base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-pro-rata-application-evidence
  "Integrity-only validator for a loaded application evidence profile.

   A passing result verifies the profile schema and self-hash only. It does not
   rederive application or write-back semantics without source artifacts."
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
    (doseq [f [:evidence-profile/allocation-evidence-hash
               :evidence-profile/propagation-hash
               :evidence-profile/application-hash
               :evidence-profile/verification]]
      (when-not (contains? profile f)
        (swap! errors conj (str "missing " (name f)))))
    (doseq [f [:evidence-profile/allocation-evidence-hash
               :evidence-profile/propagation-hash
               :evidence-profile/application-hash
               :evidence-profile/hash]
            :when (not (hash-ref/valid-sha256-ref? (get profile f)))]
      (swap! errors conj (str "invalid canonical SHA-256 reference in " (name f))))
    (when (and (some? (:evidence-profile/partial-fill-decision-root profile))
               (not (hash-ref/valid-sha256-ref?
                     (:evidence-profile/partial-fill-decision-root profile))))
      (swap! errors conj "invalid canonical SHA-256 reference in partial-fill-decision-root"))
    (when (and (= :verified (:evidence-profile/verification-status profile))
               (not (hash-ref/valid-sha256-ref?
                     (:evidence-profile/partial-fill-decision-root profile))))
      (swap! errors conj "verified application profile must bind a source decision root"))
    (when-not (string? (:evidence-profile/hash profile))
      (swap! errors conj "missing evidence-profile/hash"))
    (when (string? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            domain (if (= schema-version-v1 (:schema-version profile))
                     :pro-rata-application-evidence
                     :pro-rata-application-evidence-v2)
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

(defn verify-pro-rata-application-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Returns {:valid? bool :mismatches [...]}"
  [profile & args]
  (if-not (= schema-version (:schema-version profile))
    {:valid? false :verification-level :semantic :status :blocked
     :semantic-status :not-run
     :errors [:legacy-profile-has-no-source-bound-semantic-verification]}
    (let [integrity (validate-pro-rata-application-evidence profile)
          recomputed (apply build-pro-rata-application-evidence args)
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
               (= :blocked (:evidence-profile/verification-status recomputed)) :blocked
               :else :invalid)
     :semantic-status (:evidence-profile/verification-status recomputed)
     :profile-recomputed recomputed
     :mismatches @mismatches})))
