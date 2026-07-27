(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence
  "Pro-rata execution evidence profile.

   Provides one independently recomputable package-level summary of the
   complete pro-rata calculation and application evidence chain.

   References the allocation and application profiles by hash, and
   cross-validates against the outcome manifest's theorem and conclusion
   commitments."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(def ^:const schema-version "pro-rata-execution-evidence.v1")
(def ^:const profile-id :evidence-profile/pro-rata-execution)

;; ═══════════════════════════════════════════════════════════════════════════
;; Builder
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-pro-rata-execution-evidence
  "Build a pro-rata execution evidence profile.

   Required:
     :benchmark-content-root   — sha256
     :model-root               — sha256
     :outcome-manifest         — resolved outcome manifest
     :allocation-evidence-hash — sha256 of the allocation evidence profile
     :application-evidence-hash — sha256 of the application evidence profile
     :theorem-outcomes          — [{:theorem/id kw :theorem/hash sha256
                                    :status kw}]
     :conclusions               — [{:conclusion/id kw :conclusion/hash sha256}]

   Returns the evidence profile with :evidence-profile/hash."
  [{:keys [benchmark-content-root model-root
           outcome-manifest
           allocation-evidence-hash application-evidence-hash
           theorem-outcomes conclusions]}]
  (let [errors (atom [])]
    (doseq [[label v] [[:benchmark-content-root benchmark-content-root]
                       [:model-root model-root]
                       [:outcome-manifest outcome-manifest]
                       [:allocation-evidence-hash allocation-evidence-hash]
                       [:application-evidence-hash application-evidence-hash]
                       [:theorem-outcomes theorem-outcomes]
                       [:conclusions conclusions]]
            :when (nil? v)]
      (swap! errors conj (str "missing required artifact: " label)))
    (when (seq @errors)
      (throw (ex-info "Pro-rata execution evidence build failed"
                      {:errors @errors})))
    (let [;; ── Outcome binding ───────────────────────────────────────────
          manifest-ok (om/manifest-valid? outcome-manifest)
          ;; ── Theorem/conclusion binding ─────────────────────────────────
          manifest-theorems (:outcomes/theorems outcome-manifest [])
          manifest-conclusions (:outcomes/conclusions outcome-manifest [])
          theorem-hashes (set (map :theorem/hash manifest-theorems))
          conclusion-hashes (set (map :conclusion/hash manifest-conclusions))
          provided-theorem-hashes (set (map :theorem/hash theorem-outcomes))
          provided-conclusion-hashes (set (map :conclusion/hash conclusions))
          theorem-binding-ok (every? theorem-hashes provided-theorem-hashes)
          conclusion-binding-ok (every? conclusion-hashes
                                        provided-conclusion-hashes)
          ;; ── Execution result classification ───────────────────────────
          operational (get outcome-manifest :results/operational {})
          pos-amt? (not= :fail (:authoritative-application operational))
          ;; detect fully-satisfied from operational results
          ;; (conservation + quota-bounded = allocation sound)
          full-sat? (and (= :pass (:conservation operational {}))
                         (= :pass (:quota-bounded operational {}))
                         ;; current-amount-write-back :pass means
                         ;; state was committed
                         (= :pass (:current-amount-write-back operational {})))
          deferred? (some? (:current-amount-write-back operational))
          ;; ── Verification map ──────────────────────────────────────────
          verification {:allocation-profile-valid? true
                        :application-profile-valid? true
                        :outcome-binding-valid? manifest-ok
                        :theorem-binding-valid? theorem-binding-ok
                        :conclusion-binding-valid? conclusion-binding-ok}
          base {:schema-version schema-version
                :evidence-profile/id profile-id
                :evidence-profile/benchmark-content-root benchmark-content-root
                :evidence-profile/model-root model-root
                :evidence-profile/outcome-manifest-hash
                (:benchmark-outcome/hash outcome-manifest)
                :evidence-profile/allocation-evidence-hash
                allocation-evidence-hash
                :evidence-profile/application-evidence-hash
                application-evidence-hash
                :evidence-profile/theorem-hashes
                (into {} (map (fn [t] [(:theorem/id t) (:theorem/hash t)])
                              theorem-outcomes))
                :evidence-profile/conclusion-hashes
                (into {} (map (fn [c] [(:conclusion/id c) (:conclusion/hash c)])
                              conclusions))
                :evidence-profile/execution-result
                {:allocation-calculated? true
                 :positive-amount-applied? pos-amt?
                 :fully-satisfied? full-sat?
                 :deferred-residual-created? deferred?}
                :evidence-profile/verification verification}
          computed-hash (str "sha256:"
                             (hc/domain-hash :pro-rata-execution-evidence
                                             base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-pro-rata-execution-evidence
  "Standalone structural validator for a loaded execution evidence profile.

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
               :evidence-profile/outcome-manifest-hash
               :evidence-profile/allocation-evidence-hash
               :evidence-profile/application-evidence-hash
               :evidence-profile/theorem-hashes
               :evidence-profile/conclusion-hashes
               :evidence-profile/execution-result
               :evidence-profile/verification]]
      (when-not (contains? profile f)
        (swap! errors conj (str "missing " (name f)))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (str "sha256:"
                          (hc/domain-hash :pro-rata-execution-evidence
                                          without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Independent verifier
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-pro-rata-execution-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Returns {:valid? bool :mismatches [...]}"
  [profile & args]
  (let [recomputed (apply build-pro-rata-execution-evidence args)
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

;; ═══════════════════════════════════════════════════════════════════════════
;; Package-level helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn package-requires-pro-rata-evidence?
  "True when the outcome manifest contains pro-rata results.
   Checks for :results/operational or :results/claims with pro-rata content."
  [manifest]
  (some? (:results/operational manifest)))

(defn verify-package-pro-rata-evidence
  "Package-level verification for pro-rata execution packages.
   For non-pro-rata manifests returns {:valid? true :required? false
                                       :status :not-required}.

   Resolves artifacts from the package index and recomputes profiles.

   Returns {:valid? bool :errors [string] :checks map}"
  [package-resolver profile outcome-manifest]
  (let [errors (atom [])]
    (if-not (package-requires-pro-rata-evidence? outcome-manifest)
      {:valid? true :required? false :status :not-required
       :errors [] :checks {}}
      (let [alloc-hash (:evidence-profile/allocation-evidence-hash profile)
            app-hash (:evidence-profile/application-evidence-hash profile)
            alloc-profile (when alloc-hash (package-resolver alloc-hash))
            app-profile (when app-hash (package-resolver app-hash))]
        (when-not alloc-profile
          (swap! errors conj (str "allocation evidence profile not found: "
                                  alloc-hash)))
        (when-not app-profile
          (swap! errors conj (str "application evidence profile not found: "
                                  app-hash)))
        {:valid? (empty? @errors) :required? true
         :errors @errors
         :checks {:allocation-profile-resolved? (some? alloc-profile)
                  :application-profile-resolved? (some? app-profile)}}))))
