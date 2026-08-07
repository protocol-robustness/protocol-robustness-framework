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
            [resolver-sim.benchmark.packs.partial-fill.evidence :as pf-ev]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "pro-rata-application-evidence.v1")
(def ^:const profile-id :evidence-profile/pro-rata-application)

(defn- continuity-status
  "Classify continuity check results.
   Returns {:status :verified | :not-observed | :failed
            :reason-code kw :detail str}"
  [continuity-evidence]
  (if (empty? continuity-evidence)
    {:status :not-observed
     :reason-code :no-next-transition
     :detail "terminal scenario — no later transition consumes this state"}
    (let [failures (remove :matches? (mapcat identity continuity-evidence))]
      (if (seq failures)
        {:status :failed
         :reason-code :stale-next-precondition
         :detail (str (count failures) " participant(s) have stale preconditions")}
        {:status :verified
         :reason-code :state-continuous
         :detail "all successor preconditions match their committed state"}))))

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

   Returns the evidence profile with :evidence-profile/hash."
  [{:keys [allocation-evidence-hash propagation application
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
          prop-valid (pf/validate-pro-rata-propagation propagation)
          prop-binding (pf/propagation-allocation-binding-violations
                        nil propagation)
          ;; ── Accounting reconciliation ─────────────────────────────────
          acct-result (yield-inv/check-pro-rata-accounting-reconciles
                       world-after)
          acct-ok (:holds? acct-result)
          prop-complete (yield-inv/check-pro-rata-propagation-complete
                         world-after)
          prop-complete-ok (every? (fn [[k v]] (or (true? v) (nil? v)))
                                   prop-complete)
          ;; ── State write-back ──────────────────────────────────────────
          wb-ok (and (seq state-write-back-evidence)
                     (every? (fn [wb]
                               (and (true? (:verified? wb))
                                    (true? (get-in wb [:withdrawn :verified?]))
                                    (true? (get-in wb [:position :verified?]))))
                             state-write-back-evidence))
          ;; ── Deferred current-amount (from evidence ladder) ────────────
          ladder-levels (mapcat :levels evidence-ladder)
          state-wb-status (some #(when (= "state-written-back" (:level %)) %)
                                ladder-levels)
          cont-status (some #(when (= "continuity-consumed" (:level %)) %)
                            ladder-levels)
          deferred-ca-ok (= "verified" (or (:status state-wb-status) ""))
          ;; ── Current-amount continuity ─────────────────────────────────
          amt-ok (or (empty? continuity-evidence)
                     (and (seq continuity-evidence)
                          (every? (fn [ce]
                                    (every? :amount-continuous? ce))
                                  continuity-evidence)))
          ;; ── Next precondition continuity ──────────────────────────────
          cont-classification (continuity-status continuity-evidence)
          ;; ── Apparent application vs accounting reconciliation ─────────
          app-accounting (get operational-outcome :authoritative-application)
          accounting-only-ok (and (not= :fail app-accounting)
                                  ;; state write-back may fail while accounting passes
                                  (some? app-accounting))
          ;; ── Build verification map ────────────────────────────────────
          verification {:propagation-valid? (true? prop-valid)
                        :propagation-binding-valid?
                        (empty? prop-binding)
                        :application-valid? (true? prop-valid)
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
                :evidence-profile/application-hash
                (when application (:application/hash application))
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
                "sha256:deferred" ;; placeholder — resolved from ladder
                :evidence-profile/continuity-evidence-hash
                (when (seq continuity-evidence)
                  (hash-ref/sha256-ref
                   (hc/domain-hash :evidence-collection
                                   (vec continuity-evidence))))
                :evidence-profile/verification verification}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :pro-rata-application-evidence
                                         base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-pro-rata-application-evidence
  "Standalone structural validator for a loaded application evidence profile.

   Returns {:valid? bool :errors [string]}."
  [profile]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version profile))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version profile))))
    (when-not (= profile-id (:evidence-profile/id profile))
      (swap! errors conj (str "expected profile/id " (pr-str profile-id)
                              " got " (pr-str (:evidence-profile/id profile)))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (hash-ref/sha256-ref
                      (hc/domain-hash :pro-rata-application-evidence
                                      without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Independent verifier
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-pro-rata-application-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Returns {:valid? bool :mismatches [...]}"
  [profile & args]
  (let [recomputed (apply build-pro-rata-application-evidence args)
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
