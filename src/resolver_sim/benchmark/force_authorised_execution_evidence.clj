(ns resolver-sim.benchmark.force-authorised-execution-evidence
  "Force-authorised execution evidence profile.

   A derived, optional, package-bound verification artifact that commits
   to a complete force-authorisation execution chain.

   Acyclic artifact order:
     policy → review-round → authorisation → reservation
     → outcome manifest → consumption receipt → evidence profile

   The profile references artifacts by hash, embeds none.
   All verification booleans are recomputed by the builder — never
   caller-supplied.

   Required only when the outcome manifest contains
   :execution/force-authorisation.  Prohibited for ordinary outcomes.

   Built only where the terminal receipt references a resulting outcome
   manifest.  A failed attempt without an outcome remains independently
   reviewable through its reservation, receipt, and terminal evidence,
   but does not produce this particular profile."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.assurance.authorised-effect-correlation :as correlation]))

(def ^:const schema-version "force-authorised-execution-evidence.v1")

(def ^:const profile-id :evidence-profile/force-authorised-execution)
(def ^:const schema-version-v2 "force-authorised-execution-evidence.v2")
(def ^:private v2-allowed-keys
  #{:schema-version :evidence-profile/id :evidence-profile/policy-hash
    :evidence-profile/review-round-hash :evidence-profile/authorisation-hash
    :evidence-profile/reservation-hash :evidence-profile/outcome-manifest-hash
    :evidence-profile/consumption-receipt-hash :evidence-profile/executed-content-root
    :evidence-profile/execution-result :evidence-profile/verification
    :execution/effect-correlation-hash :evidence-profile/hash})

;; ═══════════════════════════════════════════════════════════════════════════
;; Builder
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-force-authorised-execution-evidence
  "Build a force-authorised execution evidence profile.

   Takes resolved artifacts (not hashes).  Calls every existing validator
   to derive the verification map — never accepts caller-supplied booleans.

   Required:
     authorisation        — resolved force-authorisation artifact
     policy               — resolved FA policy artifact (string-keyed)
     review-round         — resolved review-round artifact
     reservation          — resolved reservation artifact
     outcome-manifest     — resolved benchmark outcome manifest
     consumption-receipt  — resolved consumption receipt artifact
     public-key-resolver  — fn (researcher-id) -> public-key-path

   The profile is built only when the receipt references a resulting
   outcome manifest.  A failed attempt without an outcome does not
   produce this profile.

   Returns the evidence profile with :evidence-profile/hash.
   Throws on invalid inputs or failed verification."
  [{:keys [authorisation policy review-round reservation
           outcome-manifest consumption-receipt public-key-resolver]}]
  (let [errors (atom [])]
    ;; Ensure all artifacts are present
    (doseq [[label artifact] [[:authorisation authorisation]
                              [:policy policy]
                              [:review-round review-round]
                              [:reservation reservation]
                              [:outcome-manifest outcome-manifest]
                              [:consumption-receipt consumption-receipt]]
            :when (nil? artifact)]
      (swap! errors conj (str "missing required artifact: " label)))
    (when-not public-key-resolver
      (swap! errors conj "missing required :public-key-resolver"))
    ;; Receipt must reference an outcome manifest for this profile type
    (let [o-hash (:consumption/resulting-outcome-hash consumption-receipt)]
      (when-not (some? o-hash)
        (swap! errors conj (str "consumption-receipt has no resulting-outcome-hash; "
                                "evidence profile requires an outcome-producing execution"))))
    (when (seq @errors)
      (throw (ex-info "Evidence profile build failed" {:errors @errors})))
    ;; ── Call each existing validator ────────────────────────────────────
    (let [auth-val (rfa/validate-authorisation authorisation)
          sig-result (rfa/verify-decision-signatures public-key-resolver
                                                     authorisation)
          policy-val (rfa/verify-against-policy policy authorisation)
          round-val (rfa/verify-against-round review-round authorisation)
          binding-val (rfa/verify-fa-binding outcome-manifest authorisation
                                             reservation)
          receipt-val (rfa/verify-consumption-receipt consumption-receipt
                                                      reservation
                                                      outcome-manifest)
          receipt-status (:consumption/status consumption-receipt)
          ;; ── Derive verification map (never caller-supplied) ──────────
          verification {:authorisation-valid? (:valid? auth-val)
                        :decision-signatures-valid? (:valid? sig-result)
                        :policy-binding-valid? (:valid? policy-val)
                        :review-round-binding-valid? (:valid? round-val)
                        :manifest-binding-valid? (:consistent? binding-val)
                        :receipt-binding-valid? (:consistent? receipt-val)}
          ;; ── Derive execution result ───────────────────────────────────
          execution-result
          (case receipt-status
            :consumed
            {:terminal-status :consumed
             :outcome-produced? true
             :successful-authorised-outcome? true}
            :failed-after-consumption
            {:terminal-status :failed-after-consumption
             :outcome-produced? true
             :successful-authorised-outcome? false}
            :rolled-back-after-consumption
            {:terminal-status :rolled-back-after-consumption
             :outcome-produced? true
             :successful-authorised-outcome? false}
            {:terminal-status :unknown
             :outcome-produced? false
             :successful-authorised-outcome? false})
          ;; ── Build profile ─────────────────────────────────────────────
          base {:schema-version schema-version
                :evidence-profile/id profile-id
                :evidence-profile/policy-hash
                (:policy/hash (:authorisation/policy authorisation))
                :evidence-profile/review-round-hash
                (:review-round/hash (:authorisation/review-round authorisation))
                :evidence-profile/authorisation-hash
                (:authorisation/hash authorisation)
                :evidence-profile/reservation-hash
                (:reservation/hash reservation)
                :evidence-profile/outcome-manifest-hash
                (:benchmark-outcome/hash outcome-manifest)
                :evidence-profile/consumption-receipt-hash
                (:consumption/hash consumption-receipt)
                :evidence-profile/executed-content-root
                (or (get-in outcome-manifest
                            [:execution/force-authorisation
                             :executed-content-root])
                    (:benchmark/content-root outcome-manifest))
                :evidence-profile/execution-result execution-result
                :evidence-profile/verification verification}
          computed-hash (str "sha256:"
                             (hc/domain-hash :force-authorised-execution-evidence
                                             base))]
      ;; If any verification failed, the profile is still built so that
      ;; the failing evidence chain is recorded.  The independent verifier
      ;; must recompute and compare these values.
      (assoc base :evidence-profile/hash computed-hash))))

(defn build-force-authorised-execution-evidence-v2
  "Build status-aware v2 execution evidence. Produced/reversed effects require
   the same validated correlation referenced by the receipt; explicit no-effect
   failures prohibit a correlation rather than inventing a synthetic effect."
  [{:keys [correlation consumption-receipt] :as fields}]
  (when-not (= "force-authorisation-consumption.v2"
                (:schema-version consumption-receipt))
    (throw (ex-info "Execution evidence v2 requires a receipt v2" {})))
  (when-not (:valid? (rfa/validate-consumption-receipt consumption-receipt))
    (throw (ex-info "Execution evidence v2 requires a valid receipt v2" {})))
  (let [outcome (:consumption/effect-outcome consumption-receipt)
        receipt-hash (:consumption/effect-correlation-hash consumption-receipt)
        supplied (:execution/effect-correlation-hash fields)
        requires-correlation? (contains? #{:produced :reversed} outcome)]
    (when-not (contains? #{:not-produced :produced :reversed} outcome)
      (throw (ex-info "Invalid v2 receipt effect outcome" {:effect-outcome outcome})))
    (when (and (= outcome :not-produced)
               (or correlation receipt-hash supplied))
      (throw (ex-info "No-effect execution evidence must not carry a correlation"
                      {:error :unexpected-effect-correlation})))
    (when requires-correlation?
      (when-not (correlation/valid-correlation? correlation)
        (throw (ex-info "Invalid effect correlation" {})))
      (when-not (= receipt-hash (:correlation/hash correlation))
        (throw (ex-info "Receipt/correlation hash mismatch" {})))
      (when (and supplied (not= supplied (:correlation/hash correlation)))
        (throw (ex-info "Supplied execution correlation hash conflicts with correlation artifact" {}))))
    (let [v1 (build-force-authorised-execution-evidence
              (dissoc fields :correlation :execution/effect-correlation-hash))
          base (cond-> (assoc (dissoc v1 :evidence-profile/hash)
                              :schema-version schema-version-v2)
                 requires-correlation?
                 (assoc :execution/effect-correlation-hash (:correlation/hash correlation)))]
      (assoc base :evidence-profile/hash
             (str "sha256:" (hc/domain-hash :force-authorised-execution-evidence-v2 base))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Standalone validator
;; ═══════════════════════════════════════════════════════════════════════════

(defn validate-force-authorised-execution-evidence
  "Standalone structural validator for a loaded evidence profile.
   Checks schema version, required fields, and hash integrity.

   Returns {:valid? bool :errors [string]}."
  [profile]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version profile))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version profile))))
    (when-not (= profile-id (:evidence-profile/id profile))
      (swap! errors conj (str "expected profile/id " (pr-str profile-id)
                              " got " (pr-str (:evidence-profile/id profile)))))
    (doseq [f [:evidence-profile/policy-hash
               :evidence-profile/review-round-hash
               :evidence-profile/authorisation-hash
               :evidence-profile/reservation-hash
               :evidence-profile/outcome-manifest-hash
               :evidence-profile/consumption-receipt-hash
               :evidence-profile/execution-result
               :evidence-profile/verification]]
      (when-not (contains? profile f)
        (swap! errors conj (str "missing " (name f)))))
    (let [v (:evidence-profile/verification profile)
          required-keys [:authorisation-valid? :decision-signatures-valid?
                         :policy-binding-valid? :review-round-binding-valid?
                         :manifest-binding-valid? :receipt-binding-valid?]]
      (doseq [k required-keys]
        (when-not (contains? v k)
          (swap! errors conj (str "missing verification key " (name k))))))
    (when (some? (:evidence-profile/hash profile))
      (let [without-hash (dissoc profile :evidence-profile/hash)
            computed (str "sha256:"
                          (hc/domain-hash :force-authorised-execution-evidence
                                          without-hash))]
        (when-not (= computed (:evidence-profile/hash profile))
          (swap! errors conj (str "profile/hash mismatch: declared "
                                  (:evidence-profile/hash profile)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn validate-force-authorised-execution-evidence-v2
  "Strict structural validator for v2. Correlation resolution remains a
   terminal-chain concern, while its canonical hash reference is authenticated
   in this artifact's v2 preimage."
  [profile]
  (let [correlation-hash (:execution/effect-correlation-hash profile)
        errors (cond-> []
                 (not= schema-version-v2 (:schema-version profile)) (conj :unsupported-evidence-version)
                 (not (every? v2-allowed-keys (keys profile))) (conj :unknown-evidence-key)
                 (and correlation-hash (not (re-matches #"sha256:[0-9a-f]{64}" correlation-hash)))
                 (conj :invalid-effect-correlation-reference)
                 (not= (:evidence-profile/hash profile)
                       (str "sha256:" (hc/domain-hash :force-authorised-execution-evidence-v2
                                                       (dissoc profile :evidence-profile/hash))))
                 (conj :evidence-hash-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn validate-force-authorised-execution-evidence-any
  "Version-dispatched structural validator. V1 rejects appended v2 fields."
  [profile]
  (case (:schema-version profile)
    "force-authorised-execution-evidence.v2"
    (validate-force-authorised-execution-evidence-v2 profile)
    "force-authorised-execution-evidence.v1"
    {:valid? (and (:valid? (validate-force-authorised-execution-evidence profile))
                  (not (contains? profile :execution/effect-correlation-hash)))
     :errors (cond-> (:errors (validate-force-authorised-execution-evidence profile))
               (contains? profile :execution/effect-correlation-hash) (conj :v2-field-on-v1))}
    {:valid? false :errors [:unsupported-evidence-version]}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Independent verifier
;; ═══════════════════════════════════════════════════════════════════════════

(defn verify-force-authorised-execution-evidence
  "Independent verification: recompute the evidence profile from resolved
   artifacts and compare hash and findings.

   Takes the stored profile and the same artifacts passed to
   build-force-authorised-execution-evidence.

   Returns {:valid? bool :profile-recomputed map
            :mismatches [{:field kw :stored value :recomputed value}]}"
  [profile & args]
  (let [recomputed (apply build-force-authorised-execution-evidence args)
        mismatches (atom [])]
    ;; Compare profile-hash
    (when-not (= (:evidence-profile/hash profile)
                 (:evidence-profile/hash recomputed))
      (swap! mismatches conj {:field :evidence-profile/hash
                              :stored (:evidence-profile/hash profile)
                              :recomputed (:evidence-profile/hash recomputed)}))
    ;; Compare verification map
    (let [v-stored (:evidence-profile/verification profile)
          v-recomp (:evidence-profile/verification recomputed)]
      (doseq [k (keys v-stored)]
        (when-not (= (get v-stored k) (get v-recomp k))
          (swap! mismatches conj
                 {:field k
                  :stored (get v-stored k)
                  :recomputed (get v-recomp k)}))))
    {:valid? (empty? @mismatches)
     :profile-recomputed recomputed
     :mismatches @mismatches}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Package-level helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn package-requires-evidence-profile?
  "True when the manifest's :execution/force-authorisation section is present."
  [manifest]
  (some? (:execution/force-authorisation manifest)))

(defn verify-package-force-authorised-execution
  "Package-level verification for force-authorised execution packages.

   Resolves artifacts from the package index using the provided resolver,
   recomputes the evidence profile, and checks consistency.

   package-resolver — fn (artifact-sha256) -> resolved artifact map | nil
   profile           — the stored evidence profile artifact
   outcome-manifest  — the stored outcome manifest

   Optional:
     :public-key-resolver  — fn (researcher-id) -> public-key-path
                             (default: throws, which will cause verification
                              to fail when signature verification is needed)

   The resolver MUST resolve the exact artifacts matching the manifest's
   committed hashes — not accept arbitrary caller-supplied maps.

   Returns {:valid? bool :errors [string] :checks map}"
  [package-resolver profile outcome-manifest & {:keys [public-key-resolver]
                                                :or {public-key-resolver
                                                     (fn [id]
                                                       (throw
                                                        (ex-info
                                                         "public-key-resolver not available"
                                                         {:researcher/id id})))}}]
  (let [errors (atom [])
        checks (atom {})
        fa-sec (:execution/force-authorisation outcome-manifest)]
    (if-not fa-sec
      (do (swap! errors conj "no :execution/force-authorisation section")
          {:valid? false :errors @errors :checks {}})
      (let [auth-hash (:authorisation-hash fa-sec)
            auth (when auth-hash (package-resolver auth-hash))]
        (swap! checks assoc :has-fa-section? true)
        (when-not auth (swap! errors conj (str "authorisation not found: " auth-hash)))
        (let [policy-ref (get-in auth [:authorisation/policy :policy/hash])
              policy (when policy-ref (package-resolver policy-ref))]
          (when-not policy (swap! errors conj (str "policy not found: " policy-ref)))
          (let [round-ref (get-in auth [:authorisation/review-round :review-round/hash])
                round (when round-ref (package-resolver round-ref))]
            (when-not round (swap! errors conj (str "review-round not found: " round-ref)))
            (let [res-hash (:reservation-hash fa-sec)
                  reservation (when res-hash (package-resolver res-hash))]
              (when-not reservation (swap! errors conj (str "reservation not found: " res-hash)))
              (let [receipt-hash (:evidence-profile/consumption-receipt-hash profile)
                    receipt (when receipt-hash (package-resolver receipt-hash))]
                (when-not receipt (swap! errors conj (str "receipt not found: " receipt-hash)))
                (swap! checks assoc
                       :authorisation-resolved? (some? auth)
                       :policy-resolved? (some? policy)
                       :round-resolved? (some? round)
                       :reservation-resolved? (some? reservation)
                       :receipt-resolved? (some? receipt))
                (when (and auth policy round reservation receipt)
                  (try
                    (let [recomputed (build-force-authorised-execution-evidence
                                      {:authorisation auth
                                       :policy policy
                                       :review-round round
                                       :reservation reservation
                                       :outcome-manifest outcome-manifest
                                       :consumption-receipt receipt
                                       :public-key-resolver public-key-resolver})]
                      (swap! checks assoc :evidence-recomputed? true)
                      (if (= (:evidence-profile/hash profile)
                             (:evidence-profile/hash recomputed))
                        (swap! checks assoc :profile-hash-match? true)
                        (do (swap! errors conj "recomputed profile hash mismatch")
                            (swap! checks assoc :profile-hash-match? false))))
                    (catch Exception e
                      (swap! errors conj (str "evidence recomputation failed: "
                                              (.getMessage e))))))
                {:valid? (empty? @errors)
                 :errors @errors
                 :checks @checks}))))))))
