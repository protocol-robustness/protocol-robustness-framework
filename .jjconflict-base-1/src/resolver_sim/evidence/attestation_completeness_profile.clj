(ns resolver-sim.evidence.attestation-completeness-profile
  "Versioned, hash-committed completeness profiles for attestation bundles.

   A completeness profile defines what evidence is required, optional, or
   sensitivity-controlled for an attestation bundle, and how missing data
   is treated.  The profile is committed by hash in the bundle manifest,
   so the same evidence cannot be reinterpreted under different rules.

   Usage:
     (require '[resolver-sim.evidence.attestation-completeness-profile :as acp])

     (def profile (acp/make-profile :review {}))
     (acp/profile-hash profile)
     (acp/evaluate-evidence-status profile evidence-state)
     (acp/validate-profile profile)"
  (:require [clojure.set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── Supported profiles ────────────────────────────────────────────────────

(def ^:const profile-schema-version "attestation-completeness-profile.v1")

(defn- make-required-evidence-set
  "Evidence categories that MUST be present for this profile."
  [mode]
  (case mode
    :review #{:attestation-records :claim-results}
    :development #{}
    #{}))

(defn- make-optional-evidence-set
  "Evidence categories that MAY be present; absence is not a failure."
  [mode]
  (case mode
    :review #{:evidence-nodes :registry-snapshots}
    :development #{:attestation-records :claim-results :evidence-nodes :registry-snapshots}
    #{}))

(defn- make-sensitivity-controlled-evidence-set
  "Evidence categories that may be intentionally withheld under a committed
   sensitivity decision.  Absence is acceptable only when the bundle carries
   a valid sensitivity report authorizing the withholding."
  [mode]
  (case mode
    :review #{:attestation-records :evidence-nodes}
    :development #{}
    #{}))

(defn- make-rules
  "Construct the rules map for a profile mode."
  [mode]
  {:evidence/required (make-required-evidence-set mode)
   :evidence/optional (make-optional-evidence-set mode)
   :evidence/sensitivity-controlled (make-sensitivity-controlled-evidence-set mode)
   :signature/required false
   :sensitivity/missing-decision (case mode
                                   :review :fail
                                   :development :warn
                                   :fail)
   :sensitivity/empty-evidence-set (case mode
                                     :review :fail
                                     :development :warn
                                     :fail)})

;; ── Profile hashing ───────────────────────────────────────────────────────

(defn- project-evidence-sets
  "Explicitly project the profile's evidence category sets to sorted vectors
   for canonical hashing.  Sets are outside the canonical type algebra; the
   caller requests the set→sorted-vector normalization explicitly here (it is
   never performed silently by the encoder)."
  [profile]
  (update-in profile [:profile/rules]
             (fn [rules]
               (-> rules
                   (update :evidence/required #(vec (sort %)))
                   (update :evidence/optional #(vec (sort %)))
                   (update :evidence/sensitivity-controlled #(vec (sort %)))))))

(defn profile-hash
  "Compute the canonical hash of a completeness profile.
   The hash covers all fields except :profile/hash itself, after explicitly
   projecting evidence category sets to sorted vectors."
  [profile]
  (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record}
                                            (project-evidence-sets
                                             (dissoc profile :profile/hash)))))

;; ── Profile construction ──────────────────────────────────────────────────

(defn make-profile
  "Create an attestation completeness profile.

   Arguments:
     mode — :review (strict) or :development (permissive)
     opts — map with optional overrides:
       :required-evidence-categories     — set of keywords
       :optional-evidence-categories     — set of keywords
       :sensitivity-controlled-categories — set of keywords
       :missing-sensitivity-decision     — :fail or :warn
       :empty-evidence-set-decision      — :fail or :warn

   Returns a profile map with :profile/mode, :profile/rules,
   :profile/schema-version, and (after hash) :profile/hash."
  [mode opts]
  (let [base {:profile/schema-version profile-schema-version
              :profile/mode mode
              :profile/rules (make-rules mode)}
        overrides {:evidence/required (:required-evidence-categories opts)
                   :evidence/optional (:optional-evidence-categories opts)
                   :evidence/sensitivity-controlled (:sensitivity-controlled-categories opts)
                   :signature/required (:signature-required opts)
                   :sensitivity/missing-decision (:missing-sensitivity-decision opts)
                   :sensitivity/empty-evidence-set (:empty-evidence-set-decision opts)}
        merged (reduce-kv (fn [m k v]
                            (if (some? v)
                              (assoc-in m [:profile/rules k] v)
                              m))
                          base overrides)]
    (assoc merged :profile/hash (profile-hash merged))))

;; ── Evidence status derivation ─────────────────────────────────────────────

(defn- category-from-object-kind
  "Map an object kind keyword to an evidence category keyword."
  [kind]
  (case kind
    :attestation-record :attestation-records
    :claim-result :claim-results
    :evidence-node :evidence-nodes
    :registry-snapshot :registry-snapshots
    nil))

(defn evaluate-evidence-status
  "Determine the normative bundle verification status from the profile rules
   and the actual evidence state.

   Arguments:
     profile       — completeness profile map
     evidence-state — map with:
       :bundle/objects         — vector of object descriptors
       :sensitivity/decision   — :allowed, :blocked, or nil
       :sensitivity/report-hash — optional hash string

   Returns one of:
     :fully-verified
     :hash-linked
     :partially-verified
     :blocked-by-sensitivity-policy
     :invalid

   Field-level fidelity note: this profile classifies by evidence CATEGORY, not by
   settlement field detail. Terminal-settlement evidence artifacts that lack
   :finalize/write-down (legacy artifacts) cannot provide field-level evidence
   fidelity for the negative-yield write-down reconciliation. :fully-verified here
   attests category completeness only; it must not be read as attesting the
   write-down reconciliation. When such evidence is present but field-level
   fidelity cannot be asserted, the narrowest existing downgraded status is
   :hash-linked (present + hash-committed, not field-verified). Field-level
   fidelity is determined by resolver-sim.assurance.custody/
   verify-settlement-evidence-fidelity."
  [profile evidence-state]
  (let [rules (:profile/rules profile)
        required (:evidence/required rules)
        sensitivity-controlled (:evidence/sensitivity-controlled rules)
        missing-decision (:sensitivity/missing-decision rules)
        empty-decision (:sensitivity/empty-evidence-set rules)

        objects (:bundle/objects evidence-state [])
        present-categories (set (keep (comp category-from-object-kind :object/kind) objects))

        missing-required (clojure.set/difference required present-categories)
        missing-sensitivity (clojure.set/difference sensitivity-controlled present-categories)

        sensitivity-decision (:sensitivity/decision evidence-state)

        has-missing-required? (seq missing-required)
        has-missing-sensitivity? (seq missing-sensitivity)
        has-empty? (empty? objects)

        sensitivity-blocks? (and (seq missing-sensitivity)
                                 (not= :allowed sensitivity-decision))]

    (cond
      ;; Empty set with profile requiring content → invalid
      (and has-empty? (= :fail empty-decision))
      :invalid

      ;; Missing sensitivity-controlled evidence under blocked decision
      (and sensitivity-blocks? (= :fail missing-decision))
      :blocked-by-sensitivity-policy

      ;; Missing required evidence
      (and has-missing-required? (= :fail (if (some (partial contains? sensitivity-controlled) missing-required)
                                            :warn
                                            missing-decision)))
      :invalid

      ;; All required present, some optional missing → hash-linked
      (and (not has-missing-required?)
           (not has-missing-sensitivity?)
           (seq present-categories))
      :hash-linked

      ;; Everything required is present (all required categories covered).
      ;; N.B. this checks REQUIRED -> present, not the reverse: previously it
      ;; verified only that every present category was a known category, which
      ;; let a bundle missing a required category (e.g. :attestation-records)
      ;; be mislabelled :fully-verified.
      (clojure.set/subset? required present-categories)
      :fully-verified

      ;; Partially verified permissive mode
      :else
      :partially-verified)))

;; ── Profile validation ────────────────────────────────────────────────────

(defn validate-profile
  "Validate a completeness profile map.  Throws on invalid profiles."
  [profile]
  (let [sv (:profile/schema-version profile)]
    (when-not (= sv profile-schema-version)
      (throw (ex-info "Unsupported completeness profile schema"
                      {:expected profile-schema-version :actual sv}))))
  (let [mode (:profile/mode profile)
        rules (:profile/rules profile)]
    (when-not (contains? #{:review :development} mode)
      (throw (ex-info "Unsupported completeness profile mode"
                      {:mode mode})))
    (let [required (:evidence/required rules)
          optional (:evidence/optional rules)
          sensitivity (:evidence/sensitivity-controlled rules)]
      (when-not (every? (fn [s] (and (set? s) (every? keyword? s)))
                        [required optional sensitivity])
        (throw (ex-info "Evidence categories must be sets of keywords"
                        {:required required :optional optional :sensitivity sensitivity})))
      (let [signature-required (:signature/required rules)]
        (when-not (contains? #{true false nil} signature-required)
          (throw (ex-info ":signature/required must be true, false, or nil"
                          {:signature/required signature-required}))))
      (let [missing-decision (:sensitivity/missing-decision rules)
            empty-decision (:sensitivity/empty-evidence-set rules)]
        (when-not (every? #(contains? #{:fail :warn} %) [missing-decision empty-decision])
          (throw (ex-info "Decisions must be :fail or :warn"
                          {:missing-decision missing-decision :empty-decision empty-decision}))))))
  (let [computed (profile-hash profile)
        declared (:profile/hash profile)]
    (when (and declared (not= computed declared))
      (throw (ex-info "Completeness profile hash mismatch"
                      {:declared declared :computed computed}))))
  profile)

;; ── Known profiles ────────────────────────────────────────────────────────

(def review-profile
  "Strict/review completeness profile.  Missing required evidence or
   sensitivity-controlled evidence without an explicit :allowed decision
   produces :invalid status."
  (make-profile :review {}))

(def development-profile
  "Permissive/development completeness profile.  Missing evidence is downgraded
   to warnings rather than failures."
  (make-profile :development {}))

(def ^:private profile-registry
  {:review review-profile
   :development development-profile})

(defn resolve-profile
  "Look up a known profile by mode keyword."
  [mode]
  (let [p (get profile-registry mode)]
    (when-not p
      (throw (ex-info "Unknown completeness profile mode"
                      {:mode mode :supported (vec (keys profile-registry))})))
    p))
