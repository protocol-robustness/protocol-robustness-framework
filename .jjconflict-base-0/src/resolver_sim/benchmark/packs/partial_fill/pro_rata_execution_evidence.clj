(ns resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence
  "Pro-rata execution evidence profile.

    Provides one independently recomputable package-level summary of the
    complete pro-rata calculation and application evidence chain.

    References the allocation and application profiles by hash, and
    cross-validates against the outcome manifest's theorem and conclusion
    commitments.

   Versioned: pro-rata-execution-evidence.v1 (legacy) and
   pro-rata-execution-evidence.v2 (current). v2 corrects the overclaiming v1
   execution-result key :current-amount-write-back-verified? to the explicitly
   operational :current-write-back-operational-pass? and is the recommended
   producer for new runs; v1 remains for backward compatibility with existing
   canonical artifacts.

   LEGACY SEMANTICS (frozen, v1 only): the v1 key
   :current-amount-write-back-verified? is a legacy spelling for the AGGREGATE
   current-amount write-back operational pass. It MUST NOT be interpreted as
   independent per-obligation write-back verification — operational write-back
   can pass for a zero or haircut result. Consumers needing per-obligation
   verification must rely on the stronger authoritative fact
   :application-write-back-verified? (see :authoritative-application)."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-allocation-evidence :as alloc-ev]
            [resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence :as app-ev]))

(def ^:const schema-version "pro-rata-execution-evidence.v1")
(def ^:const schema-version-v2 "pro-rata-execution-evidence.v2")
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

    Optional:
      :creation/provenance        — :in-band | :out-of-band (defaults to :in-band)

    Returns the evidence profile with :evidence-profile/hash."
  [{:keys [benchmark-content-root model-root
           outcome-manifest
           allocation-evidence-hash application-evidence-hash
           theorem-outcomes conclusions
           creation/provenance]}]
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
          theorem-binding-ok (if (empty? theorem-hashes)
                               true
                               (every? #(contains? provided-theorem-hashes %) theorem-hashes))
          conclusion-binding-ok (if (empty? conclusion-hashes)
                                  true
                                  (every? #(contains? provided-conclusion-hashes %) conclusion-hashes))
           ;; ── Execution result classification ───────────────────────────
          operational (get outcome-manifest :results/operational {})
           ;; These are execution-evidence status facts, not per-obligation
           ;; settlement classifications. Do not expose them as amount/full-fill
           ;; predicates: operational write-back can pass for a zero or haircut
           ;; result, and claim admission derives full-fill from decision rows.
          application-verified? (= :pass (:authoritative-application operational))
          allocation-sound? (and (= :pass (:conservation operational {}))
                                 (= :pass (:quota-bounded operational {}))
                                 (= :pass (:current-amount-write-back operational {})))
          deferred-write-back-verified? (= :pass (:current-amount-write-back operational {}))
           ;; ── Verification map ──────────────────────────────────────────
          verification {:outcome-binding-valid? manifest-ok
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
                :evidence-profile/creation
                {:provenance (or provenance :in-band)}
                :evidence-profile/execution-result
                {:allocation-calculated? true
                 :application-write-back-verified? application-verified?
                 :allocation-sound? allocation-sound?
                 :current-amount-write-back-verified? deferred-write-back-verified?
                   ;; LEGACY (v1 only): this key is a legacy spelling for the
                   ;; AGGREGATE current-amount write-back operational pass. It
                   ;; MUST NOT be interpreted as per-obligation write-back
                   ;; verification; operational write-back can pass for a zero
                   ;; or haircut result. v2 replaces it with the non-overclaiming
                   ;; :current-write-back-operational-pass?.
                   ;; Compatibility fields: this aggregate profile lacks the
                   ;; per-obligation filled/deferred/haircut rows required to
                   ;; establish any of these settlement facts, so it must NOT emit
                   ;; them as false negatives. Each is marked explicitly unevaluated
                   ;; rather than hardcoded false, so a consumer can never mistake
                   ;; an unknown for a known-negative.
                 :positive-amount-applied-unevaluated? true
                 :fully-satisfied-unevaluated? true
                 :deferred-residual-created-unevaluated? true}
                :evidence-profile/verification verification}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :pro-rata-execution-evidence
                                         base))]
      (assoc base :evidence-profile/hash computed-hash))))

;; ═══════════════════════════════════════════════════════════════════════════
;; v2 builder
;; ═══════════════════════════════════════════════════════════════════════════
;; v2 corrects the operational-fact naming in :evidence-profile/execution-result.
;; The v1 key :current-amount-write-back-verified? implied a verified per-obligation
;; current-amount write-back, but it actually carried the AGGREGATE operational
;; write-back status (which the surrounding block itself warns can pass for a zero
;; or haircut result). v2 renames it to the explicitly operational
;; :current-write-back-operational-pass? and keeps the operational vs
;; authoritative distinction: the stronger authoritative fact remains the separate
;; :application-write-back-verified? (from :authoritative-application). The
;; operational pass predicate is computed ONCE and reused for both allocation-sound?
;; and the emitted operational fact. The un-establishable amount/full-fill/residual
;; facts are emitted as explicit :...-unevaluated? markers, never hardcoded false.

(defn build-pro-rata-execution-evidence-v2
  "Build a pro-rata-execution-evidence.v2 profile.

    Same inputs and cross-bindings as v1, but the emitted operational write-back
    fact is :current-write-back-operational-pass? (formerly the overclaiming v1
    key :current-amount-write-back-verified?). The (:current-amount-write-back
    operational) pass predicate is computed once and reused for both
     :allocation-sound? and the emitted operational flag; it is NOT sourced from
     :authoritative-application (that stronger fact remains :application-write-back-
     verified?). The :positive-amount-applied?, :fully-satisfied?, and
     :deferred-residual-created? settlement facts are NOT established by this
     aggregate profile; they are emitted as :...-unevaluated? markers (true),
     never as hardcoded false negatives.

    Optional:
      :creation/provenance        — :in-band | :out-of-band (defaults to :in-band)

    Returns the evidence profile with :evidence-profile/hash."
  [{:keys [benchmark-content-root model-root
           outcome-manifest
           allocation-evidence-hash application-evidence-hash
           theorem-outcomes conclusions
           creation/provenance]}]
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
          theorem-binding-ok (if (empty? theorem-hashes)
                               true
                               (every? #(contains? provided-theorem-hashes %) theorem-hashes))
          conclusion-binding-ok (if (empty? conclusion-hashes)
                                  true
                                  (every? #(contains? provided-conclusion-hashes %) conclusion-hashes))
          ;; ── Execution result classification ───────────────────────────
          operational (get outcome-manifest :results/operational {})
          ;; These are execution-evidence status facts, not per-obligation
          ;; settlement classifications. Do not expose them as amount/full-fill
          ;; predicates: operational write-back can pass for a zero or haircut
          ;; result, and claim admission derives full-fill from decision rows.
          application-verified? (= :pass (:authoritative-application operational))
          ;; Compute the operational write-back pass predicate ONCE and reuse it
          ;; for both allocation-sound? and the emitted operational fact.
          current-wb-operational-ok? (= :pass (:current-amount-write-back operational {}))
          allocation-sound? (and (= :pass (:conservation operational {}))
                                 (= :pass (:quota-bounded operational {}))
                                 current-wb-operational-ok?)
          ;; ── Verification map ──────────────────────────────────────────
          verification {:outcome-binding-valid? manifest-ok
                        :theorem-binding-valid? theorem-binding-ok
                        :conclusion-binding-valid? conclusion-binding-ok}
          base {:schema-version schema-version-v2
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
                :evidence-profile/creation
                {:provenance (or provenance :in-band)}
                :evidence-profile/execution-result
                {:allocation-calculated? true
                 :application-write-back-verified? application-verified?
                 :allocation-sound? allocation-sound?
                 :current-write-back-operational-pass?
                 current-wb-operational-ok?
                  ;; Compatibility fields: this aggregate profile lacks the
                  ;; per-obligation filled/deferred/haircut rows required to
                  ;; establish any of these settlement facts, so it must NOT emit
                  ;; them as false negatives. Each is marked explicitly unevaluated
                  ;; rather than hardcoded false.
                 :positive-amount-applied-unevaluated? true
                 :fully-satisfied-unevaluated? true
                 :deferred-residual-created-unevaluated? true}
                :evidence-profile/verification verification}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :pro-rata-execution-evidence-v2
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
    (let [er (:evidence-profile/execution-result profile)]
      (when (and er (contains? er :positive-amount-applied?))
        (swap! errors conj (str "execution-result must not emit the factual "
                                ":positive-amount-applied?")))
      (doseq [unevaluated [:positive-amount-applied-unevaluated?
                           :fully-satisfied-unevaluated?
                           :deferred-residual-created-unevaluated?]]
        (when (and er (not (contains? er unevaluated)))
          (swap! errors conj (str "execution-result missing explicit unevaluated marker "
                                  (name unevaluated))))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (hash-ref/sha256-ref
                      (hc/domain-hash :pro-rata-execution-evidence
                                      without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

;; ── v2 validator + version dispatch ─────────────────────────────────────────

(def ^:private v2-required-fields
  [:evidence-profile/benchmark-content-root
   :evidence-profile/model-root
   :evidence-profile/outcome-manifest-hash
   :evidence-profile/allocation-evidence-hash
   :evidence-profile/application-evidence-hash
   :evidence-profile/theorem-hashes
   :evidence-profile/conclusion-hashes
   :evidence-profile/execution-result
   :evidence-profile/verification])

(defn validate-pro-rata-execution-evidence-v2
  "Standalone structural validator for a pro-rata-execution-evidence.v2 profile.

     v2 requires the operational write-back fact
     :current-write-back-operational-pass? in :evidence-profile/execution-result
     and rejects the overclaiming v1 key :current-amount-write-back-verified? under
     the v2 schema. Recomputes the hash under the v2 domain tag.

     Execution-result semantics enforced:
       - :current-write-back-operational-pass? must be present and exactly boolean;
       - the factual settlement keys :positive-amount-applied?, :fully-satisfied?,
         and :deferred-residual-created? must be ABSENT (the aggregate profile
         cannot establish them — they are represented by their unevaluated markers);
       - the three :...-unevaluated? markers must each be present and exactly true;
       - the remaining execution-result predicates (:allocation-calculated?,
         :application-write-back-verified?, :allocation-sound?) must be boolean.

     Returns {:valid? bool :errors [string]}."
  [profile]
  (let [errors (atom [])]
    (when-not (= schema-version-v2 (:schema-version profile))
      (swap! errors conj (str "expected schema-version " schema-version-v2
                              " got " (:schema-version profile))))
    (when-not (= profile-id (:evidence-profile/id profile))
      (swap! errors conj (str "expected profile/id " (pr-str profile-id)
                              " got " (pr-str (:evidence-profile/id profile)))))
    (doseq [f v2-required-fields]
      (when-not (contains? profile f)
        (swap! errors conj (str "missing " (name f)))))
    (let [er (:evidence-profile/execution-result profile)]
      ;; Required v2 operational fact: present and exactly boolean.
      (when (and er (not (contains? er :current-write-back-operational-pass?)))
        (swap! errors conj (str "v2 execution-result missing "
                                ":current-write-back-operational-pass?")))
      (when (and er (contains? er :current-write-back-operational-pass?)
                 (not (boolean? (:current-write-back-operational-pass? er))))
        (swap! errors conj (str "v2 execution-result :current-write-back-operational-pass? "
                                "must be boolean")))
      ;; v1 overclaiming key rejected under the v2 schema.
      (when (and er (contains? er :current-amount-write-back-verified?))
        (swap! errors conj (str "v2 execution-result must not carry the v1 "
                                "overclaiming :current-amount-write-back-verified?")))
      ;; The three factual settlement keys are NOT established by this aggregate
      ;; profile; their presence is a schema error (must be absent).
      (doseq [factual [:positive-amount-applied?
                       :fully-satisfied?
                       :deferred-residual-created?]]
        (when (and er (contains? er factual))
          (swap! errors conj (str "execution-result must not emit the factual "
                                  (name factual)))))
      ;; The corresponding unevaluated markers must be present AND exactly true.
      (doseq [unevaluated [:positive-amount-applied-unevaluated?
                           :fully-satisfied-unevaluated?
                           :deferred-residual-created-unevaluated?]]
        (when (and er (not (contains? er unevaluated)))
          (swap! errors conj (str "execution-result missing explicit unevaluated marker "
                                  (name unevaluated))))
        (when (and er (contains? er unevaluated)
                   (not (true? (get er unevaluated))))
          (swap! errors conj (str "execution-result unevaluated marker "
                                  (name unevaluated) " must be exactly true"))))
      ;; The remaining execution-result predicates are boolean facts.
      (doseq [flag [:allocation-calculated?
                    :application-write-back-verified?
                    :allocation-sound?]]
        (when (and er (contains? er flag)
                   (not (boolean? (get er flag))))
          (swap! errors conj (str "execution-result " (name flag) " must be boolean")))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (hash-ref/sha256-ref
                      (hc/domain-hash :pro-rata-execution-evidence-v2
                                      without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn validate-pro-rata-execution-evidence-any
  "Version-dispatched structural validator. v2 rejects the v1 overclaiming key
    under the v2 schema; v1 rejects the v2 operational-pass key under the v1 schema.

    Returns {:valid? bool :errors [string]}."
  [profile]
  (case (:schema-version profile)
    "pro-rata-execution-evidence.v2"
    (validate-pro-rata-execution-evidence-v2 profile)
    "pro-rata-execution-evidence.v1"
    (let [er (:evidence-profile/execution-result profile)
          {validation :valid? errors :errors}
          (validate-pro-rata-execution-evidence profile)]
      {:valid? (and validation
                    (not (contains? er :current-write-back-operational-pass?)))
       :errors (cond-> (vec (or errors []))
                 (contains? er :current-write-back-operational-pass?)
                 (conj :v2-field-on-v1))})
    {:valid? false
     :errors [(str "unsupported schema-version "
                   (pr-str (:schema-version profile)))]}))
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-pro-rata-execution-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Re-extracts :creation/provenance from the stored profile and passes it
   to the rebuilder, so that an :out-of-band profile reproduces its original
   hash. The rebuilder's creation-boundary default (:in-band) must not
   override a stored :out-of-band assertion during verification.

   Returns {:valid? bool :mismatches [...]}"
  [profile & args]
  (let [stored-provenance (get-in profile [:evidence-profile/creation :provenance])
        args-map (if (and (= (count args) 1) (map? (first args)))
                   (first args)
                   (apply hash-map args))
        recomputed (build-pro-rata-execution-evidence
                    (assoc args-map :creation/provenance stored-provenance))
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

(defn verify-pro-rata-execution-evidence-v2
  "Independent v2 verification: recompute the evidence profile from resolved
   artifacts via the v2 builder and compare hash and findings.

   Re-extracts :creation/provenance from the stored profile and passes it
   to the rebuilder, so that an :out-of-band profile reproduces its original
   hash. The rebuilder's creation-boundary default (:in-band) must not
   override a stored :out-of-band assertion during verification.

   Returns {:valid? bool :mismatches [...] :profile-recomputed map}"
  [profile & args]
  (let [stored-provenance (get-in profile [:evidence-profile/creation :provenance])
        args-map (if (and (= (count args) 1) (map? (first args)))
                   (first args)
                   (apply hash-map args))
        recomputed (build-pro-rata-execution-evidence-v2
                    (assoc args-map :creation/provenance stored-provenance))
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
  "Declarative check: true only when the outcome manifest explicitly marks
    pro-rata evidence as required via :outcomes/pro-rata-evidence-required.

    Fails closed — does not infer requirement from optional fields like
    :results/operational. If the marker is absent, returns false."
  [manifest]
  (= true (:outcomes/pro-rata-evidence-required manifest)))

(defn verify-package-pro-rata-evidence
  "Package-level verification for pro-rata execution packages.
    For non-pro-rata manifests returns {:valid? true :required? false
                                        :status :not-required}.

    Resolves artifacts from the package index, validates each profile's
    structure and hash integrity, and verifies the execution profile binds
    correctly to its referenced allocation/application profiles.

    Returns {:valid? bool :errors [string] :checks map}"
  [package-resolver profile outcome-manifest]
  (let [errors (atom [])
        checks (atom {})]
    (if-not (package-requires-pro-rata-evidence? outcome-manifest)
      {:valid? true :required? false :status :not-required
       :errors [] :checks {}}
      (let [alloc-hash (:evidence-profile/allocation-evidence-hash profile)
            app-hash (:evidence-profile/application-evidence-hash profile)
            alloc-profile (when alloc-hash (package-resolver alloc-hash))
            app-profile (when app-hash (package-resolver app-hash))]
         ;; Resolve checks
        (when-not alloc-profile
          (swap! errors conj (str "allocation evidence profile not found: "
                                  alloc-hash)))
        (when-not app-profile
          (swap! errors conj (str "application evidence profile not found: "
                                  app-hash)))
         ;; Validate allocation profile (structural + hash recomputation)
        (when alloc-profile
          (let [alloc-validation (alloc-ev/validate-pro-rata-allocation-evidence alloc-profile)]
            (swap! checks assoc :allocation-profile-valid? (:valid? alloc-validation))
            (when-not (:valid? alloc-validation)
              (swap! errors conj (str "allocation profile validation failed: "
                                      (pr-str (:errors alloc-validation)))))))
         ;; Validate application profile (structural + hash recomputation)
        (when app-profile
          (let [app-validation (app-ev/validate-pro-rata-application-evidence app-profile)]
            (swap! checks assoc :application-profile-valid? (:valid? app-validation))
            (when-not (:valid? app-validation)
              (swap! errors conj (str "application profile validation failed: "
                                      (pr-str (:errors app-validation)))))))
         ;; Verify execution profile binds to resolved profiles
        (swap! checks assoc
               :allocation-profile-bound? (= alloc-hash (:evidence-profile/hash alloc-profile))
               :application-profile-bound? (= app-hash (:evidence-profile/hash app-profile)))
        (when (and alloc-profile (not= alloc-hash (:evidence-profile/hash alloc-profile)))
          (swap! errors conj (str "allocation profile hash mismatch: expected "
                                  alloc-hash " got "
                                  (:evidence-profile/hash alloc-profile))))
        (when (and app-profile (not= app-hash (:evidence-profile/hash app-profile)))
          (swap! errors conj (str "application profile hash mismatch: expected "
                                  app-hash " got "
                                  (:evidence-profile/hash app-profile))))
        {:valid? (empty? @errors) :required? true
         :errors @errors
         :checks @checks}))))
