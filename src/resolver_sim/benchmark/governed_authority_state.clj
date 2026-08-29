(ns resolver-sim.benchmark.governed-authority-state
  "Stage B authenticated state view for governed-authority resolution.

  The store is an operational dependency and is deliberately not committed into
  resolution artifacts. Its atom is the publication boundary for an authority
  envelope and the state-addressed material it authenticates."
  (:require [clojure.set :as set]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-governance-evidence :as evidence]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-result-receipt :as result-receipt]
            [resolver-sim.benchmark.governed-authority-authorisation :as governed-authorisation]
            [resolver-sim.benchmark.three-member-authority-report :as authority-report]

            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const envelope-schema "authoritative-state-envelope.v1")
(def ^:const envelope-v2-schema "authoritative-state-envelope.v2")
(def ^:const envelope-domain :authoritative-state-envelope-v1)
(def ^:const envelope-v2-domain :authoritative-state-envelope-v2)

(def envelope-fields
  #{:artifact/schema :chain-instance-genesis/root :execution/state-root
    :chain-configuration/root :review-governance/root
    :review-governance-activation/root :configuration-head/root
    :control-plane-evidence/root :publication/sequence
    :publication/predecessor-root :position-time-index/root})

(defn- root [value]
  (ref/sha256-ref
   (hc/domain-hash envelope-domain
                   (hc/project-canonical-safe
                    (dissoc value :authoritative-state-envelope/root)))))

(defn validate-envelope [envelope]
  (let [have (if (map? envelope) (set (keys envelope)) #{})
        errors (cond-> []
                 (not (map? envelope)) (conj "envelope must be a map")
                 (and (map? envelope) (not= envelope-schema (:artifact/schema envelope)))
                 (conj "artifact/schema is invalid")
                 (and (map? envelope)
                      (seq (set/difference have envelope-fields
                                           #{:authoritative-state-envelope/root})))
                 (conj "envelope has unknown keys")
                 (and (map? envelope) (seq (set/difference envelope-fields have)))
                 (conj "envelope has missing keys")
                 (and (map? envelope)
                      (not (and (integer? (:publication/sequence envelope))
                                (not (neg? (:publication/sequence envelope))))))
                 (conj "publication/sequence must be non-negative")
                 (and (map? envelope)
                      (not (or (nil? (:publication/predecessor-root envelope))
                               (ref/valid-sha256-ref? (:publication/predecessor-root envelope)))))
                 (conj "publication/predecessor-root is invalid"))]
    {:valid? (empty? errors) :errors errors}))

(defn envelope-root [envelope]
  (let [result (validate-envelope envelope)]
    (when-not (:valid? result)
      (throw (ex-info "authoritative-state-envelope.v1 is invalid" result)))
    (root envelope)))

(defn build-envelope [envelope]
  (let [base (assoc envelope :artifact/schema envelope-schema)]
    (doseq [field (disj envelope-fields :artifact/schema :publication/sequence
                        :publication/predecessor-root)]
      (when-not (ref/valid-sha256-ref? (get base field))
        (throw (ex-info "authority envelope root field is invalid" {:field field}))))
    (assoc base :authoritative-state-envelope/root (root base))))

(defn- envelope-v2-root [envelope]
  (ref/sha256-ref
   (hc/domain-hash envelope-v2-domain
                   (hc/project-canonical-safe
                    (dissoc envelope :authoritative-state-envelope/root)))))

(defn build-envelope-v2
  "Production V2 builder. The configuration-head commitment is derived solely
   from an actual valid configuration-head-state.v1 body."
  [envelope head-state]
  (when (and (contains? envelope :artifact/schema)
             (not= envelope-v2-schema (:artifact/schema envelope)))
    (throw (ex-info "v1 envelope cannot be admitted as v2" {})))
  (when-not (configuration-head/valid-head-state? head-state)
    (throw (ex-info "configuration head state is invalid" {})))
  (let [base (assoc envelope
                    :artifact/schema envelope-v2-schema
                    :configuration-head/root (configuration-head/head-state-root head-state))]
    (doseq [field (disj envelope-fields :artifact/schema :publication/sequence
                        :publication/predecessor-root)]
      (when-not (ref/valid-sha256-ref? (get base field))
        (throw (ex-info "authority envelope v2 root field is invalid" {:field field}))))
    (assoc base :authoritative-state-envelope/root (envelope-v2-root base))))

(defn verify-envelope-v2
  "Verify the V2 envelope/head body join. The committed head root must be the
   canonical root of the supplied head-state and its active configuration must
   be the envelope configuration."
  [envelope head-state]
  (and (= envelope-v2-schema (:artifact/schema envelope))
       (= envelope-fields (set (keys (dissoc envelope :authoritative-state-envelope/root))))
       (configuration-head/valid-head-state? head-state)
       (= (:configuration-head/root envelope) (configuration-head/head-state-root head-state))
       (= (:chain-configuration/root envelope) (:configuration/head-root head-state))
       (= (:authoritative-state-envelope/root envelope) (envelope-v2-root envelope))))

(deftype AuthorityStateStore [state])

(defn- freeze-data
  "Recursively freeze a value into canonical-safe form, rejecting callbacks,
    functions, and any non-canonical mutable/runtime objects. Maps are rebuilt
    preserving key order; duplicate keys in the textual source are already
    resolved by the reader, but duplicate entries in vectors are detected by
    closed-shape validators on the bodies."
  [value]
  (cond
    (or (nil? value) (boolean? value) (string? value) (keyword? value)
        (integer? value) (ratio? value)) value
    (map? value) (into {} (map (fn [[k v]] [(freeze-data k) (freeze-data v)]) value))
    (vector? value) (mapv freeze-data value)
    (set? value) (into #{} (map freeze-data value))
    :else (throw (ex-info "authority material contains runtime or mutable value"
                          {:class (some-> value class .getName)}))))

(defn- freeze-material [material]
  (freeze-data material))

(def ^:const signer-key-set-schema "governed-authority-signer-key-set.v1")
(def ^:const signer-key-algorithm :ed25519)

(def ^:private signer-key-entry-fields
  "Closed entry fields of a signer-key-set entry: researcher identity, signing-key
    identity, algorithm, and public-key material. No path or alias is accepted —
    only the material itself so that no externally resolved reference can enter
    the canonical root."
  #{:researcher/id :signing-key/id :signing-key/algorithm :signing-key/public-key})

(def ^:private signer-key-set-fields
  "Closed top-level shape of governed-authority-signer-key-set.v1."
  #{:artifact/schema :signer-key-set/entries})

(defn- validate-signer-key-entry
  "Closed-shape validation for a single signer-key-set entry."
  [entry]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? entry)
      (report! "signer-key-set entry must be a map"))
    (when (map? entry)
      (let [have (set (keys entry))
            extra (set/difference have signer-key-entry-fields)
            missing (set/difference signer-key-entry-fields have)]
        (when (seq extra)
          (report! (str "signer-key entry has unknown keys: " (sort-by str extra))))
        (when (seq missing)
          (report! (str "signer-key entry missing keys: " (sort-by str missing))))
        (when-not (string? (:researcher/id entry))
          (report! ":researcher/id must be a string"))
        (when-not (string? (:signing-key/id entry))
          (report! ":signing-key/id must be a string"))
        (when-not (= signer-key-algorithm (:signing-key/algorithm entry))
          (report! (str ":signing-key/algorithm must be " signer-key-algorithm)))
        (when-not (and (string? (:signing-key/public-key entry))
                       (re-matches #"[0-9a-f]{64}" (:signing-key/public-key entry)))
          (report! ":signing-key/public-key must be a 64-char lowercase hex string"))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn validate-signer-key-set
  "Closed-shape validation for a signer-key-set: no unknown top-level keys,
    each entry closed, unique researcher/key-pair enforcement (no duplicate
    [researcher-id, signing-key-id] pairs)."
  [key-set]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? key-set)
      (report! "signer-key-set must be a map"))
    (when (map? key-set)
      (let [have (set (keys key-set))
            extra (set/difference have signer-key-set-fields)
            missing (set/difference signer-key-set-fields have)]
        (when-not (= signer-key-set-schema (:artifact/schema key-set))
          (report! (str "artifact/schema must be " signer-key-set-schema)))
        (when (seq extra)
          (report! (str "signer-key-set has unknown keys: " (sort-by str extra))))
        (when (seq missing)
          (report! (str "signer-key-set missing keys: " (sort-by str missing))))
        (when-let [entries (:signer-key-set/entries key-set)]
          (when-not (vector? entries)
            (report! ":signer-key-set/entries must be a vector"))
          (when (vector? entries)
            (doseq [entry entries]
              (let [entry-validation (validate-signer-key-entry entry)]
                (when-not (:valid? entry-validation)
                  (doseq [e (:errors entry-validation)]
                    (report! (str "signer-key entry: " e))))))
            (let [pairs (map (juxt :researcher/id :signing-key/id) entries)]
              (when (not= (count pairs) (count (set pairs)))
                (report! "signer-key-set has duplicate researcher/key-pair entries")))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn signer-key-set-root
  "Compute the canonical SHA-256 root of a governed-authority-signer-key-set.v1.

    Validates strict closed-shape first (fail-closed); a caller-supplied
    expected root may be passed as the second argument and is rejected on
    mismatch."
  ([key-set]
   (let [validation (validate-signer-key-set key-set)]
     (when-not (:valid? validation)
       (throw (ex-info "governed signer-key-set is invalid"
                       {:errors (:errors validation)})))
     (let [base (select-keys key-set [:artifact/schema :signer-key-set/entries])]
       (ref/sha256-ref
        (hc/domain-hash :governed-authority-signer-key-set-v1
                        (hc/project-canonical-safe base))))))
  ([key-set expected]
   (let [computed (signer-key-set-root key-set)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied signer-key-set root does not match computed root"
                       {:type :signer-key-set/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

(def ^:const review-round-material-domain :governed-authority-review-round-v1)
(def ^:const review-round-material-schema rr/governed-schema-version)

(def ^:private review-round-material-fields
  "Closed field set of the authenticated review-round body. Includes the full
   governed-round projection required by rr/governed-round?: chain-configuration
   root, governance root, epoch, constitution time, policy id and hash, plus the
   base identity fields and the schema version."
  #{:artifact/schema :schema-version :benchmark/content-root :review-round/members
    :review-round/membership-frozen-at :review-round/policy-root
    :review-round/purpose
    :review-round/chain-configuration-root :review-round/governance-root
    :review-round/governance-epoch :review-round/constituted-at
    :review-round/policy-id :review-round/policy-hash})

(defn- validate-review-round-material
  "Closed-shape validation for the review-round body: rejects unknown top-level
     keys, missing required keys, and wrong schema version."
  [round]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? round)
      (report! "review-round material must be a map"))
    (when (map? round)
      (let [have (set (keys round))
            extra (set/difference have review-round-material-fields)
            missing (set/difference review-round-material-fields have)]
        (when-not (= review-round-material-schema (:artifact/schema round))
          (report! (str ":artifact/schema must be " review-round-material-schema)))
        (when-not (= rr/governed-schema-version (:schema-version round))
          (report! (str ":schema-version must be " rr/governed-schema-version)))
        (when (seq extra)
          (report! (str "review-round material has unknown keys: " (sort-by str extra))))
        (when (seq missing)
          (report! (str "review-round material missing keys: " (sort-by str missing))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn review-round-material-root
  "Canonical store-level root of an authenticated review-round body. Commits
     the full governed-round identity projection as a canonical sha256
     reference so the root representation matches every other material root.
     Uses rr/round-identity-input to produce the v2 governance-aware projection.
     Validates strict closed-shape first (fail-closed)."
  [round]
  (when-not (map? round)
    (throw (ex-info "governed review-round material is invalid" {})))
  (let [validation (validate-review-round-material round)]
    (when-not (:valid? validation)
      (throw (ex-info "governed review-round material is invalid"
                      {:type :review-round-material/invalid
                       :errors (:errors validation)})))
    (let [identity-input (rr/round-identity-input round (:review-round/members round))]
      (ref/sha256-ref
       (hc/domain-hash review-round-material-domain
                       (hc/project-canonical-safe identity-input))))))

;; ── Canonical position-time index artifact ──────────────────────────

(def ^:const position-time-index-schema "governed-authority-position-time-index.v1")
(def ^:const position-time-index-domain :governed-authority-position-time-index-v1)

(def ^:private position-time-index-entry-fields
  "Closed entry fields: committed position root, acceptance root, and accepted
    time. The mapping is from position-root to (acceptance-root, accepted-at)."
  #{:position/root :review-position-acceptance/root :position-time/accepted-at})

(def ^:private position-time-index-fields
  "Closed top-level shape of governed-authority-position-time-index.v1.
    Cross-references the existing position-time-basis commitment and the
    review-round identity so the index cannot drift from the frozen round."
  #{:artifact/schema :position-time-basis/root :review-round/root
    :position-time-index/entries})

(defn- validate-position-time-index-entry [entry]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? entry)
      (report! "position-time-index entry must be a map"))
    (when (map? entry)
      (let [have (set (keys entry))
            extra (set/difference have position-time-index-entry-fields)
            missing (set/difference position-time-index-entry-fields have)]
        (when (seq extra)
          (report! (str "position-time-index entry has unknown keys: " (sort-by str extra))))
        (when (seq missing)
          (report! (str "position-time-index entry missing keys: " (sort-by str missing))))
        (when-not (ref/valid-sha256-ref? (:position/root entry))
          (report! ":position/root must be a valid sha256 reference"))
        (when-not (ref/valid-sha256-ref? (:review-position-acceptance/root entry))
          (report! ":review-position-acceptance/root must be a valid sha256 reference"))
        (when-not (integer? (:position-time/accepted-at entry))
          (report! ":position-time/accepted-at must be an integer"))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn validate-position-time-index
  "Closed-shape validation for a position-time index: no unknown top-level keys,
    entry fields closed, unique position roots, and cross-reference roots are
    valid sha256 references."
  [index]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? index)
      (report! "position-time-index must be a map"))
    (when (map? index)
      (let [have (set (keys index))
            extra (set/difference have position-time-index-fields)
            missing (set/difference position-time-index-fields have)]
        (when-not (= position-time-index-schema (:artifact/schema index))
          (report! (str "artifact/schema must be " position-time-index-schema)))
        (when (seq extra)
          (report! (str "position-time-index has unknown keys: " (sort-by str extra))))
        (when (seq missing)
          (report! (str "position-time-index missing keys: " (sort-by str missing))))
        (when-not (ref/valid-sha256-ref? (:position-time-basis/root index))
          (report! ":position-time-basis/root must be a valid sha256 reference"))
        (when-not (ref/valid-sha256-ref? (:review-round/root index))
          (report! ":review-round/root must be a valid sha256 reference"))
        (when-let [entries (:position-time-index/entries index)]
          (when-not (vector? entries)
            (report! ":position-time-index/entries must be a vector"))
          (when (vector? entries)
            (doseq [entry entries]
              (let [entry-validation (validate-position-time-index-entry entry)]
                (when-not (:valid? entry-validation)
                  (doseq [e (:errors entry-validation)]
                    (report! (str "position-time-index entry: " e))))))
            (let [position-roots (map :position/root entries)]
              (when (not= (count position-roots) (count (set position-roots)))
                (report! "position-time-index has duplicate position roots")))))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn position-time-index-root
  "Compute the canonical SHA-256 root of a governed-authority-position-time-index.v1.
    Validates strict closed-shape first (fail-closed); a caller-supplied
    expected root may be passed as the second argument and is rejected on
    mismatch."
  ([index]
   (let [validation (validate-position-time-index index)]
     (when-not (:valid? validation)
       (throw (ex-info "governed position-time-index is invalid"
                       {:errors (:errors validation)})))
     (let [base (select-keys index [:artifact/schema :position-time-basis/root
                                    :review-round/root :position-time-index/entries])]
       (ref/sha256-ref
        (hc/domain-hash position-time-index-domain
                        (hc/project-canonical-safe base))))))
  ([index expected]
   (let [computed (position-time-index-root index)]
     (when (and (some? expected) (not= computed expected))
       (throw (ex-info "caller-supplied position-time-index root does not match computed root"
                       {:type :position-time-index/root-mismatch
                        :declared expected
                        :computed computed})))
     computed)))

;; ── Governance eligibility cross-check ────────────────────────────────

(defn signer-key-eligible-in-governance?
  "Verify that every signer-key-set entry is governance-eligible under the
    frozen governance body: each researcher id is a known governed member, each
    signing-key id is an active eligible key for that member's principal.
    Returns {:eligible? bool :errors [...]}."
  [key-set governance]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? key-set)
      (report! "signer-key-set must be a map"))
    (when-not (map? governance)
      (report! "governance body must be a map"))
    (when (and (map? key-set) (map? governance))
      (when-let [valid (seq (:signer-key-set/entries key-set))]
        (doseq [entry valid]
          (let [researcher-id (:researcher/id entry)
                key-id (:signing-key/id entry)]
            (when-not (governance/principal-by-id governance researcher-id)
              (report! (str "researcher " researcher-id " is not a known principal in governance")))
            (when-not (governance/position-key-valid? governance researcher-id key-id)
              (report! (str "signing-key " key-id " is not eligible for researcher " researcher-id)))))))
    {:eligible? (empty? @errors) :errors (vec @errors)}))

;; ── Store-derived lookups (no caller-supplied keys) ──────────────────

(defn lookup-signing-public-key
  "Store-derived public-key lookup only. Looks up the public key material for a
    given researcher-id and signing-key-id from the frozen signer-key-set body.
    Returns the hex public-key string or nil when no match exists."
  [signer-key-set researcher-id signing-key-id]
  (some->> (:signer-key-set/entries signer-key-set)
           (some (fn [entry]
                   (when (and (= (:researcher/id entry) researcher-id)
                              (= (:signing-key/id entry) signing-key-id))
                     (:signing-key/public-key entry))))))

(defn lookup-position-acceptance-time
  "Store-derived time lookup only. Looks up the accepted-at time for a given
    position root from the frozen position-time-index body. Returns the integer
     timestamp or nil when no match exists."
  [position-time-index position-root]
  (some->> (:position-time-index/entries position-time-index)
           (some (fn [entry]
                   (when (= (:position/root entry) position-root)
                     (:position-time/accepted-at entry))))))

;; ── B3 signature verification (signer-key-set entry-driven) ─────────────

(defn- verify-decision-signature-with-entries
  "Verify a single decision reference's signature against a signer-key-set entry.

   signer-key-set — the frozen governed-authority-signer-key-set.v1 body
     (with :signer-key-set/entries)
   decision-ref   — the decision reference map containing
     :researcher/id, :signing-key/id, :decision/hash, and :signature

   The public key is looked up directly from the signer-key-set entries by
   [researcher-id, signing-key-id]. Fails closed when the key is not found
   or the signature does not verify."
  [signer-key-set decision-ref]
  (let [researcher-id (:researcher/id decision-ref)
        key-id (:signing-key/id decision-ref)
        public-key-hex (lookup-signing-public-key signer-key-set researcher-id key-id)]
    (cond
      (nil? public-key-hex)
      {:valid? false :reason (str "signer-key not found for researcher " researcher-id " key " key-id)}

      (not= signer-key-algorithm (:signing-key/algorithm
                                  (some #(when (and (= (:researcher/id %) researcher-id)
                                                    (= (:signing-key/id %) key-id)) %)
                                        (:signer-key-set/entries signer-key-set))))
      {:valid? false :reason "signing-key algorithm mismatch"}

      :else
      (let [stripped-hash (subs (:decision/hash decision-ref) (count "sha256:"))
            sig-value (get-in decision-ref [:signature :value])]
        (if (sed/ed25519-verify-bytes
             (.getBytes stripped-hash) sig-value public-key-hex)
          {:valid? true}
          {:valid? false :reason "signature does not verify"})))))

(defn verify-decision-signatures-with-singer-key-set
  "Verify signatures for all decision references using the B3 signer-key-set.

   Replaces the legacy `public-key-resolver` approach with direct entry
   lookup from the frozen signer-key-set body. Returns:
     {:valid? bool
      :results [{:researcher/id ... :signing-key/id ... :valid? bool :reason str}]}."
  [signer-key-set authorisation]
  (let [results (mapv
                 (fn [d]
                   (let [r (verify-decision-signature-with-entries signer-key-set d)]
                     (merge {:researcher/id (:researcher/id d)
                             :signing-key/id (:signing-key/id d)}
                            r)))
                 (:authorisation/decision-references authorisation))]
    {:valid? (every? :valid? results)
     :results results}))

;; ── B3 governed-authority evaluator entry point ──────────────────────────

(defn evaluate-authority-with-frozen-material
  "Governed-authority evaluator entry point that derives all lookup functions
   internally from frozen authority material.

   Consumes:
     {:review-round full-governed-round
      :review-governance ...
      :position-time-index ...
      :signer-key-set ...}

   Derives:
   - researcher-public-key-resolver: (researcher-id signing-key-id) -> hex public key
   - position-time-resolver: position-root -> accepted-at integer
   - governance-current?: always true (frozen material is assumed current)

   Delegates to `resolver-sim.assurance.three-member-authority/evaluate-governed-authority`
   with internally-derived resolvers, replacing the legacy external key resolver
   boundary."
  [{:keys [authorisation review-round review-governance position-time-index signer-key-set]}]
  {:pre [(map? review-round) (map? review-governance)
         (map? position-time-index) (map? signer-key-set)]}
  (let [signature-valid?
        (fn [position]
          (let [result (verify-decision-signature-with-entries
                        signer-key-set
                        position)]
            (:valid? result)))]
    (authority/evaluate-governed-authority
     :authorisation authorisation
     :review-round review-round
     :governance review-governance
     :governance-current? (fn [_ _] true)
     :signature-valid? signature-valid?)))

(def ^:const report-verification-basis-schema "governed-authority-report-verification-basis.v1")
(def ^:const report-verification-basis-domain :governed-authority-report-verification-basis-v1)
(def ^:const report-verification-basis-fields
  #{:artifact/schema :governed-authority-authorisation/root
    :resolved-review-authority-context/root :authority-evaluation-basis/root
    :predecessor-authoritative-state-envelope/root :predecessor-configuration-head/root
    :chain-configuration/root :authority-semantics-policy/root
    :authority-semantics/root :three-member-authority-report/root})

(defn report-verification-basis-root [basis]
  (ref/sha256-ref (hc/domain-hash report-verification-basis-domain
                                  (hc/project-canonical-safe
                                   (dissoc basis :governed-authority-report-verification-basis/root)))))

(defn validate-report-verification-basis [basis]
  (let [expected (conj report-verification-basis-fields
                       :governed-authority-report-verification-basis/root)
        errors (cond-> []
                 (not (map? basis)) (conj "report verification basis must be a map")
                 (and (map? basis) (not= expected (set (keys basis))))
                 (conj "report verification basis has missing or unknown keys")
                 (and (map? basis) (not= report-verification-basis-schema (:artifact/schema basis)))
                 (conj "report verification basis schema is unsupported")
                 (and (map? basis) (not (every? #(ref/valid-sha256-ref? (get basis %))
                                                (disj report-verification-basis-fields :artifact/schema))))
                 (conj "report verification basis has invalid dependency root")
                 (and (map? basis) (not= (:governed-authority-report-verification-basis/root basis)
                                         (report-verification-basis-root basis)))
                 (conj "report verification basis root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-report-verification-basis [basis]
  (let [candidate (assoc (select-keys basis (disj report-verification-basis-fields :artifact/schema))
                         :artifact/schema report-verification-basis-schema)
        result (assoc candidate :governed-authority-report-verification-basis/root
                      (report-verification-basis-root candidate))]
    (when-not (:valid? (validate-report-verification-basis result))
      (throw (ex-info "report verification basis is invalid"
                      (validate-report-verification-basis result))))
    result))

(def ^:const evaluation-basis-schema "governed-authority-evaluation-basis.v1")

(def ^:const evaluation-basis-fields
  #{:resolved-review-authority-context/root :review-round/root
    :review-governance/root :position-time-basis/root
    :position-time-index/root :signer-key-set/root})

(defn evaluation-basis-root
  "Domain-separated semantic root for the exact closed evaluation-basis.v1
   projection. This is intentionally not a storage-byte identity."
  [basis]
  (ref/sha256-ref
   (hc/domain-hash :governed-authority-evaluation-basis-v1
                   (hc/project-canonical-safe
                    (dissoc basis :authority-evaluation-basis/root)))))

(defn validate-evaluation-basis
  "Validate only the intrinsic closed evaluation-basis.v1 contract: schema,
   exact fields, six dependency-reference shapes, and the self-recomputed
   semantic root. The referenced bodies and authority semantics are external
   dependencies and are deliberately not re-evaluated here."
  [basis]
  (let [expected (conj evaluation-basis-fields :artifact/schema
                       :authority-evaluation-basis/root)
        errors (cond-> []
                 (not (map? basis)) (conj "evaluation basis must be a map")
                 (and (map? basis) (not= expected (set (keys basis))))
                 (conj "evaluation basis has missing or unknown keys")
                 (and (map? basis) (not= evaluation-basis-schema (:artifact/schema basis)))
                 (conj "evaluation basis schema is unsupported")
                 (and (map? basis)
                      (not (every? #(ref/valid-sha256-ref? (get basis %))
                                   evaluation-basis-fields)))
                 (conj "evaluation basis contains an invalid dependency root")
                 (and (map? basis)
                      (not= (:authority-evaluation-basis/root basis)
                            (evaluation-basis-root basis)))
                 (conj "evaluation basis root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn verify-evaluation-basis [basis]
  (:valid? (validate-evaluation-basis basis)))

(defn evaluation-basis
  "Construct the existing closed evaluation-basis.v1 projection. Construction
   delegates to the same root and validation path used by detached readers."
  [parts]
  (let [base (select-keys parts evaluation-basis-fields)
        candidate (assoc base :artifact/schema evaluation-basis-schema)
        basis (assoc candidate :authority-evaluation-basis/root
                     (evaluation-basis-root candidate))
        validation (validate-evaluation-basis basis)]
    (when-not (:valid? validation)
      (throw (ex-info "governed-authority-evaluation-basis is invalid" validation)))
    basis))

(defn read-governed-authority-authorisation
  [store authorisation-root]
  (let [authorisation (get-in @(.state store) [:governed-authority-authorisations authorisation-root])
        validation (when authorisation (governed-authorisation/validate-authorisation authorisation))]
    (cond
      (not (ref/valid-sha256-ref? authorisation-root)) {:resolved? false :reason :authorisation-root-invalid}
      (nil? authorisation) {:resolved? false :reason :authorisation-unavailable}
      (not (:valid? validation)) {:resolved? false :reason :authorisation-invalid :errors (:errors validation)}
      (not= authorisation-root (:governed-authority-authorisation/root authorisation))
      {:resolved? false :reason :authorisation-root-mismatch}
      :else {:resolved? true :authorisation authorisation})))

(defn read-three-member-authority-report
  [store report-root]
  (let [report (get-in @(.state store) [:three-member-authority-reports report-root])]
    (cond
      (not (ref/valid-sha256-ref? report-root)) {:resolved? false :reason :authority-report-root-invalid}
      (nil? report) {:resolved? false :reason :authority-report-unavailable}
      (not (authority-report/verify-report report)) {:resolved? false :reason :authority-report-invalid}
      (not= report-root (:three-member-authority-report/root report))
      {:resolved? false :reason :authority-report-root-mismatch}
      :else {:resolved? true :authority-report report})))

(defn read-resolved-review-authority-context
  "Resolve a retained resolved-context by semantic root and run its existing
   closed rooted validator. This establishes only context integrity, not its
   predecessor-state membership."
  [store context-root]
  (let [context (get-in @(.state store) [:resolved-review-authority-contexts context-root])
        validation (when context (resolution/validate-resolved-context context))]
    (cond
      (not (ref/valid-sha256-ref? context-root)) {:resolved? false :reason :resolved-context-root-invalid}
      (nil? context) {:resolved? false :reason :resolved-context-unavailable}
      (not (:valid? validation)) {:resolved? false :reason :resolved-context-invalid :errors (:errors validation)}
      (not= context-root (:resolved-review-authority-context/root context))
      {:resolved? false :reason :resolved-context-root-mismatch}
      :else {:resolved? true :resolved-context context})))

(defn read-evaluation-basis
  "Resolve one retained evaluation-basis body by its semantic root and verify
   the exact root/body join. This is an in-memory read-side contract; later P1
   physical storage may use a distinct byte checksum but must preserve this
   semantic lookup and recomputation rule."
  [store basis-root]
  (cond
    (not (ref/valid-sha256-ref? basis-root))
    {:resolved? false :reason :evaluation-basis-root-invalid}

    :else
    (let [basis (get-in @(.state store) [:authority-evaluation-bases basis-root])
          validation (when basis (validate-evaluation-basis basis))]
      (cond
        (nil? basis) {:resolved? false :reason :evaluation-basis-unavailable}
        (not (:valid? validation)) {:resolved? false :reason :evaluation-basis-invalid
                                    :errors (:errors validation)}
        (not= basis-root (:authority-evaluation-basis/root basis))
        {:resolved? false :reason :evaluation-basis-root-mismatch}
        :else {:resolved? true :evaluation-basis basis}))))

(def ^:private authority-material-fields
  "Closed top-level field set of authenticated authority material.
    Root fields commit the material bodies; authority-material/* fields carry
    the frozen bodies themselves.  Unknown keys are rejected."
  #{:chain-instance-genesis/root :chain-configuration/root
    :review-governance/root :review-governance-activation/root
    :control-plane-evidence/root :review-governance-admissibility/root
    :review-round/hash :review-round/root
    :position-time-basis/root :position-time-index/root
    :signer-key-set/root
    :authority-material/review-round :authority-material/review-governance
    :authority-material/position-time-index :authority-material/signer-key-set})

(defn- authenticated-material?
  "Closed-shape gate on authority material: no unknown top-level keys, all bodies
    present and closed-validated, all declared roots recomputed and matched,
    eligibility cross-checked, and position-time-index roots bound to the
    frozen basis and round."
  [material]
  (let [have (if (map? material) (set (keys material)) #{})
        extra (set/difference have authority-material-fields)]
    (and (map? material)
         (empty? extra)
         (every? #(contains? have %) authority-material-fields)
         (map? (:authority-material/review-round material))
         (map? (:authority-material/review-governance material))
         (map? (:authority-material/position-time-index material))
         (map? (:authority-material/signer-key-set material))
         (:valid? (validate-review-round-material
                   (:authority-material/review-round material)))
         (:valid? (governance/validate-governance
                   (:authority-material/review-governance material)))
         (:valid? (validate-position-time-index
                   (:authority-material/position-time-index material)))
         (:valid? (validate-signer-key-set
                   (:authority-material/signer-key-set material)))
         (:eligible? (signer-key-eligible-in-governance?
                      (:authority-material/signer-key-set material)
                      (:authority-material/review-governance material)))
         (= (:signer-key-set/root material)
            (signer-key-set-root (:authority-material/signer-key-set material)))
         (= (:review-governance/root material)
            (governance/governance-root (:authority-material/review-governance material)))
         (= (:review-round/root material)
            (review-round-material-root (:authority-material/review-round material)))
         (= (:position-time-index/root material)
            (position-time-index-root (:authority-material/position-time-index material)))
         (= (:position-time-basis/root material)
            (get-in (:authority-material/position-time-index material)
                    [:position-time-basis/root]))
         (= (:review-round/root material)
            (get-in (:authority-material/position-time-index material)
                    [:review-round/root])))))

(defn verify-authenticated-material
  "Pure read-side verifier for the exact closed authority-material contract.
  Returns the frozen canonical material only after every embedded body, declared
  root, and governance/key eligibility join is recomputed. Durable projections
  use this instead of trusting a caller-supplied material identity."
  [material]
  (let [material (freeze-material material)]
    (when-not (authenticated-material? material)
      (throw (ex-info "authority material is not rooted and authenticated"
                      {:reason :authority-material-invalid})))
    material))

(defn- require-authenticated-material!
  "The single publication gate shared by initial construction and every
  successor path: freeze, then prove the closed authenticated shape and its
  recomputed roots before anything may enter the store."
  [material]
  (verify-authenticated-material material))

(defn new-store
  "Construct an authenticated authority-state store from one published envelope
    and its active material. `material` contains the root-bearing fields required
    by resolved-review-authority-context.v1 plus :review-round/hash."
  [envelope material]
  (let [envelope (build-envelope envelope)
        material (require-authenticated-material! material)
        state-root (:execution/state-root envelope)]
    (when-not (= (:chain-instance-genesis/root envelope)
                 (:chain-instance-genesis/root material))
      (throw (ex-info "material chain does not match envelope" {})))
    (AuthorityStateStore. (atom {:head (:authoritative-state-envelope/root envelope)
                                 :envelopes {(:authoritative-state-envelope/root envelope) envelope}
                                 :by-state {state-root (:authoritative-state-envelope/root envelope)}
                                 :material {state-root material}}))))

(defn new-store-v2
  "Construct a V2 authority store only from a verified canonical configuration
   head-state body. V1 envelopes are never inferred or upgraded here."
  [envelope head-state material]
  (let [envelope (build-envelope-v2 envelope head-state)
        material (require-authenticated-material! material)
        state-root (:execution/state-root envelope)
        head-root (:configuration-head/root envelope)]
    (when-not (and (verify-envelope-v2 envelope head-state)
                   (= (:chain-instance-genesis/root envelope)
                      (:chain-instance-genesis/root material))
                   (= (:chain-configuration/root envelope)
                      (:chain-configuration/root material))
                   (= (:review-governance/root envelope)
                      (:review-governance/root material)))
      (throw (ex-info "v2 authority envelope/material join is invalid" {})))
    (AuthorityStateStore. (atom {:head (:authoritative-state-envelope/root envelope)
                                 :envelopes {(:authoritative-state-envelope/root envelope) envelope}
                                 :by-state {state-root (:authoritative-state-envelope/root envelope)}
                                 :material {state-root material}
                                 :configuration-head-states {head-root head-state}}))))

(defn publish-successor-v2!
  "Publish a V2 successor only when both the authoritative envelope and its
   canonical head-state body verify and join authenticated material."
  [store expected-head envelope head-state material]
  (let [envelope (build-envelope-v2 envelope head-state)
        material (require-authenticated-material! material)
        root (:authoritative-state-envelope/root envelope)
        head-root (:configuration-head/root envelope)]
    (when-not (and (verify-envelope-v2 envelope head-state)
                   (= (:chain-instance-genesis/root envelope) (:chain-instance-genesis/root material))
                   (= (:chain-configuration/root envelope) (:chain-configuration/root material))
                   (= (:review-governance/root envelope) (:review-governance/root material)))
      (throw (ex-info "v2 successor envelope/material join is invalid" {})))
    (loop []
      (let [current @(.state store)]
        (cond
          (not= expected-head (:head current)) {:published? false :reason :state-not-at-required-head}
          (not= expected-head (:publication/predecessor-root envelope)) {:published? false :reason :authority-state-membership-unproven}
          :else (let [next (-> current
                               (assoc :head root)
                               (assoc-in [:envelopes root] envelope)
                               (assoc-in [:by-state (:execution/state-root envelope)] root)
                               (assoc-in [:material (:execution/state-root envelope)] material)
                               (assoc-in [:configuration-head-states head-root] head-state))]
                  (if (compare-and-set! (.state store) current next)
                    {:published? true :envelope envelope}
                    (recur))))))))

(defn publish-successor!
  "Atomically publish a successor envelope and its active material. The supplied
    predecessor must be the exact current publication head. Successor material
    passes the same authenticated publication gate as initial construction
    before it is eligible for CAS."
  [store expected-head envelope material]
  (let [envelope (build-envelope envelope)
        material (require-authenticated-material! material)]
    (when-not (= (:chain-instance-genesis/root envelope)
                 (:chain-instance-genesis/root material))
      (throw (ex-info "successor material chain does not match successor envelope" {})))
    (let [root (:authoritative-state-envelope/root envelope)]
      (loop []
        (let [current @(.state store)]
          (cond
            (not= expected-head (:head current)) {:published? false :reason :state-not-at-required-head}
            (not= expected-head (:publication/predecessor-root envelope)) {:published? false :reason :authority-state-membership-unproven}
            :else (let [next (-> current
                                 (assoc :head root)
                                 (assoc-in [:envelopes root] envelope)
                                 (assoc-in [:by-state (:execution/state-root envelope)] root)
                                 (assoc-in [:material (:execution/state-root envelope)] material))]
                    (if (compare-and-set! (.state store) current next)
                      {:published? true :envelope envelope}
                      (recur)))))))))

(defn- ancestor? [envelopes anchor candidate]
  (loop [root anchor seen #{}]
    (cond (nil? root) false
          (= root candidate) true
          (contains? seen root) false
          :else (recur (:publication/predecessor-root (get envelopes root))
                       (conj seen root)))))

(defn read-configured-authority-semantics
  "Resolve C → P → S only from root-keyed retained bodies, re-root every body,
   and require the configured policy selection. Runtime support is separately
   reported: a valid configuration never authorizes an unknown implementation."
  [store configuration-root policy-root semantics-root]
  (let [snapshot @(.state store)
        configuration (get-in snapshot [:chain-configurations configuration-root])
        policy (get-in snapshot [:authority-semantics-policies policy-root])
        semantics (get-in snapshot [:governed-authority-semantics semantics-root])
        configuration-root-fn (requiring-resolve 'resolver-sim.genesis/chain-configuration-root)
        policy-validate-fn (requiring-resolve 'resolver-sim.benchmark.authority-semantics-policy/validate-policy)
        policy-selection-fn (requiring-resolve 'resolver-sim.benchmark.authority-semantics-policy/verify-policy-selection)
        semantics-validate-fn (requiring-resolve 'resolver-sim.benchmark.governed-authority-semantics/validate-semantics)
        default-semantics (requiring-resolve 'resolver-sim.benchmark.governed-authority-semantics/default-semantics)
        configuration-valid? (try (= configuration-root (configuration-root-fn configuration))
                                  (catch Exception _ false))
        policy-valid? (and policy (:valid? (policy-validate-fn policy))
                           (= policy-root (:authority-semantics-policy/root policy)))
        semantics-valid? (and semantics (:valid? (semantics-validate-fn semantics))
                              (= semantics-root (:governed-authority-semantics/root semantics)))]
    (cond
      (not (every? ref/valid-sha256-ref? [configuration-root policy-root semantics-root]))
      {:resolved? false :reason :configured-semantics-root-invalid}
      (or (nil? configuration) (nil? policy) (nil? semantics))
      {:resolved? false :reason :configured-semantics-unavailable}
      (not configuration-valid?) {:resolved? false :reason :chain-configuration-invalid}
      (not policy-valid?) {:resolved? false :reason :authority-semantics-policy-invalid}
      (not semantics-valid?) {:resolved? false :reason :governed-authority-semantics-invalid}
      (not= policy-root (:authority-semantics-policy/root configuration))
      {:resolved? false :reason :configuration-policy-mismatch}
      (not (:valid? (policy-selection-fn policy semantics)))
      {:resolved? false :reason :policy-semantics-mismatch}
      ;; The existing dispatcher has no callback extension and rejects every
      ;; descriptor except its known default semantic profile.
      (not= semantics @default-semantics)
      {:resolved? false :reason :authority-semantics-runtime-unsupported}
      :else {:resolved? true :configuration configuration :policy policy :semantics semantics})))

(declare read-governed-authority-authorisation read-three-member-authority-report
         read-resolved-review-authority-context read-evaluation-basis
         read-predecessor-authority-state read-configured-authority-semantics)

(defn verify-report-replay-dependencies
  "Prove the historical B/X/E/H/material/C/P/S joins before evaluator replay."
  [basis evaluation-basis context predecessor cps]
  (let [material (:authenticated-material predecessor)
        envelope (:envelope predecessor)
        head (:head-state predecessor)
        checks [(= (:resolved-review-authority-context/root evaluation-basis)
                   (:resolved-review-authority-context/root context))
                (= (:review-round/root evaluation-basis) (:review-round/root material))
                (= (:review-governance/root evaluation-basis) (:review-governance/root material))
                (= (:position-time-basis/root evaluation-basis) (:position-time-basis/root material))
                (= (:position-time-index/root evaluation-basis) (:position-time-index/root material))
                (= (:signer-key-set/root evaluation-basis) (:signer-key-set/root material))
                (= (:predecessor-authoritative-state-envelope/root basis)
                   (:authoritative-state-envelope/root envelope))
                (= (:predecessor-configuration-head/root basis) (:configuration-head/root envelope))
                (= (:configuration-head/root envelope) (:configuration-head-state/root head))
                (= (:resolution/state-before-root context) (:execution/state-root envelope))
                (= (:authority-state/root context) (:authoritative-state-envelope/root envelope))
                (= (:chain-configuration/root context) (:chain-configuration/root envelope))
                (= (:chain-configuration/root basis) (:chain-configuration/root envelope))
                (= (:chain-configuration/root basis) (:configuration/head-root head))
                (= (:chain-configuration/root basis) (:chain-configuration/root material))
                (= (:review-governance/root context) (:review-governance/root material))
                (= (:review-round/root context) (:review-round/root material))
                ;; C is verified by its recomputed root in the C/P/S reader;
                ;; configuration bodies intentionally do not carry a self-root.
                (= (:authority-semantics-policy/root basis) (:authority-semantics-policy/root (:policy cps)))
                (= (:authority-semantics/root basis) (:governed-authority-semantics/root (:semantics cps)))]]
    (every? true? checks)))

(defn verify-governed-authority-report-from-basis
  "Detached, callback-free replay of one ratified configured authority report."
  [store verification-basis-root]
  (let [basis (get-in @(.state store) [:governed-authority-report-verification-bases verification-basis-root])]
    (cond
      (nil? basis) {:valid? false :reason :report-verification-basis-unavailable
                    :available-roots (keys (get @(.state store) :governed-authority-report-verification-bases {}))
                    :requested-root verification-basis-root}
      (not (:valid? (validate-report-verification-basis basis))) {:valid? false :reason :report-verification-basis-invalid}
      (not= verification-basis-root (:governed-authority-report-verification-basis/root basis)) {:valid? false :reason :report-verification-basis-root-mismatch}
      :else
      (let [a (read-governed-authority-authorisation store (:governed-authority-authorisation/root basis))
            r (read-three-member-authority-report store (:three-member-authority-report/root basis))
            x (read-resolved-review-authority-context store (:resolved-review-authority-context/root basis))
            b (read-evaluation-basis store (:authority-evaluation-basis/root basis))
            e (read-predecessor-authority-state store (:predecessor-authoritative-state-envelope/root basis)
                                                (:predecessor-configuration-head/root basis))
            cps (read-configured-authority-semantics store (:chain-configuration/root basis)
                                                     (:authority-semantics-policy/root basis)
                                                     (:authority-semantics/root basis))]
        (if-not (every? :resolved? [a r x b e cps])
          {:valid? false :reason :report-replay-dependency-unavailable
           :dependencies {:authorisation a :report r :context x :evaluation-basis b
                          :predecessor e :configured-semantics cps}}
          (if-not (verify-report-replay-dependencies basis (:evaluation-basis b)
                                                     (:resolved-context x) e cps)
            {:valid? false :reason :report-replay-join-invalid
             :join-inputs {:basis basis :evaluation-basis (:evaluation-basis b)
                           :context (:resolved-context x)}}
            (let [evaluate (requiring-resolve 'resolver-sim.benchmark.governed-authority-semantics/evaluate-authority-with-semantics)
                  material (:authenticated-material e)
                  replayed (authority-report/build-report
                            (evaluate (:semantics cps)
                                      {:authorisation (:authorisation a)
                                       :review-round (:authority-material/review-round material)
                                       :review-governance (:authority-material/review-governance material)
                                       :position-time-index (:authority-material/position-time-index material)
                                       :signer-key-set (:authority-material/signer-key-set material)}))]
              (if (and (= replayed (:authority-report r))
                       (= (:three-member-authority-report/root replayed)
                          (get-in r [:authority-report :three-member-authority-report/root])))
                {:valid? true :authority-report replayed}
                {:valid? false :reason :report-replay-mismatch
                 :stored (:authority-report r)
                 :replayed replayed
                 :different-keys (vec (filter #(not= (get (:authority-report r) %)
                                                     (get replayed %))
                                              (set (concat (keys (:authority-report r))
                                                           (keys replayed)))))}))))))))

(defn read-predecessor-authority-state
  "Read an exact retained V2 predecessor E/H/material tuple. Envelope and head
   verification plus their existing join are checked; material is then passed
   through the public authenticated-material verifier."
  [store envelope-root head-root]
  (let [snapshot @(.state store)
        envelope (get-in snapshot [:envelopes envelope-root])
        head (get-in snapshot [:configuration-head-states head-root])
        material (when envelope (get-in snapshot [:material (:execution/state-root envelope)]))]
    (cond
      (or (not (ref/valid-sha256-ref? envelope-root))
          (not (ref/valid-sha256-ref? head-root)))
      {:resolved? false :reason :predecessor-root-invalid}
      (or (nil? envelope) (nil? head) (nil? material))
      {:resolved? false :reason :predecessor-unavailable}
      (not (and (verify-envelope-v2 envelope head)
                (= envelope-root (:authoritative-state-envelope/root envelope))
                (= head-root (:configuration-head/root envelope))))
      {:resolved? false :reason :predecessor-envelope-head-invalid}
      :else
      (try
        (let [verified-material (verify-authenticated-material material)]
          {:resolved? true :envelope envelope :head-state head
           :authenticated-material verified-material})
        (catch Exception _
          {:resolved? false :reason :predecessor-material-invalid})))))

(defn resolve-governed-authority-context
  "Resolve a Stage A basis through an authenticated envelope store. Current
   admission requires the requested state to remain the current head; replay and
   audit require it to be an ancestor of the selected anchor."
  [store basis]
  (let [basis-result (resolution/validate-resolution-basis-any basis)
        snapshot @(.state store)
        state-root (:resolution/state-before-root basis)
        envelope-root (get-in snapshot [:by-state state-root])
        envelope (get-in snapshot [:envelopes envelope-root])
        material (get-in snapshot [:material state-root])
        current? (= envelope-root (:head snapshot))
        anchored? (ancestor? (:envelopes snapshot) (:resolution/anchor-root basis) envelope-root)
        purpose (:resolution/purpose basis)]
    (cond
      (not (:valid? basis-result)) {:resolved? false :reason :resolution-basis-invalid}
      (nil? envelope) {:resolved? false :reason :state-unavailable}
      (not= (:chain-instance-genesis/root basis) (:chain-instance-genesis/root envelope)) {:resolved? false :reason :state-wrong-chain}
      (and (= purpose :current-admission) (not current?)) {:resolved? false :reason :state-not-at-required-head}
      (and (not= purpose :current-admission) (not anchored?)) {:resolved? false :reason :state-not-authoritative}
      (not= (:review-round/hash basis) (:review-round/hash material)) {:resolved? false :reason :round-not-found-at-state}
      (not (every? true? [(= (:chain-configuration/root envelope) (:chain-configuration/root material))
                          (= (:review-governance/root envelope) (:review-governance/root material))
                          (= (:review-governance-activation/root envelope) (:review-governance-activation/root material))
                          (= (:control-plane-evidence/root envelope) (:control-plane-evidence/root material))
                          (= (:position-time-index/root envelope) (:position-time-index/root material))]))
      {:resolved? false :reason :authority-state-membership-unproven}
      :else {:resolved? true
             :context (resolution/build-resolved-context
                       {:resolution-basis/root (:resolution-basis/root basis)
                        :chain-instance-genesis/root (:chain-instance-genesis/root basis)
                        :resolution/state-before-root state-root
                        :authority-state/root envelope-root
                        :chain-configuration/root (:chain-configuration/root envelope)
                        :review-governance/root (:review-governance/root envelope)
                        :review-governance-activation/root (:review-governance-activation/root envelope)
                        :control-plane-evidence/root (:control-plane-evidence/root envelope)
                        :review-round/hash (:review-round/hash material)
                        :review-round/root (:review-round/root material)
                        :position-time-basis/root (:position-time-basis/root material)
                        :position-time-index/root (:position-time-index/root material)
                        :review-governance-admissibility/root (:review-governance-admissibility/root material)})})))

(defn resolve-authority-material
  "Resolve only the frozen, authenticated material for a V2 current-admission
   question. Resolution is deliberately not a finalization capability: a
   finalizable authority fence is issued only after the store evaluates an
   authorised report over this material."
  [store basis]
  (let [result (resolve-governed-authority-context store basis)]
    (if-not (and (:resolved? result) (= :current-admission (:resolution/purpose basis))
                 (= resolution/resolution-basis-v2-schema (:artifact/schema basis)))
      (assoc result :reason (or (:reason result) :current-admission-fence-required))
      (let [context (:context result)
            state-root (:resolution/state-before-root context)
            material (get-in @(.state store) [:material state-root])
            evaluation-basis
            (evaluation-basis
             {:resolved-review-authority-context/root
              (:resolved-review-authority-context/root context)
              :review-round/root (:review-round/root context)
              :review-governance/root (:review-governance/root context)
              :position-time-basis/root (:position-time-basis/root context)
              :position-time-index/root (:position-time-index/root context)
              :signer-key-set/root (:signer-key-set/root material)})]
        ;; Observations and finalizable capabilities use distinct registries and
        ;; return keys. Only the function below can populate :issued-fences.
        (loop []
          (let [current @(.state store)
                handle-id (str (java.util.UUID/randomUUID))
                record {:authority-state-envelope/root (:authority-state/root context)
                        :execution/state-root state-root
                        :publication/sequence (get-in current [:envelopes (:head current) :publication/sequence])
                        :resolved-review-authority-context/root (:resolved-review-authority-context/root context)
                        :resolution-basis/root (:resolution-basis/root basis)
                        :review-round/root (:review-round/root context)
                        :review-governance/root (:review-governance/root context)
                        :position-time-basis/root (:position-time-basis/root context)
                        :position-time-index/root (:position-time-index/root context)
                        :signer-key-set/root (:signer-key-set/root material)
                        :authority-evaluation-basis/root (:authority-evaluation-basis/root evaluation-basis)
                        :purpose :current-admission :status :observed}]
            (if (or (not= (:head current) (:authority-state/root context))
                    (not= state-root (get-in current [:envelopes (:head current) :execution/state-root])))
              {:resolved? false :reason :state-not-at-required-head}
              (let [next (assoc-in current [:observed-resolutions handle-id] record)]
                (if (compare-and-set! (.state store) current next)
                  {:resolved? true :context context :authenticated-material material
                   :evaluation-basis evaluation-basis
                   :resolution-handle {:resolution-handle/id handle-id}
                   :resolution-observation record}
                  (recur))))))))))

(defn- retained-authority-semantics
  "Resolve and validate the C4 C/P/S chain from one authoritative snapshot.
   Runtime resolution avoids a namespace cycle: the C4 admission layer depends
   on this store, while this issuer must independently reprove its retained
   bodies at the CAS boundary."
  [snapshot]
  (let [envelope (get-in snapshot [:envelopes (:head snapshot)])
        head-state (get-in snapshot [:configuration-head-states
                                     (:configuration-head/root envelope)])
        configuration-root (:chain-configuration/root envelope)
        configuration (get-in snapshot [:chain-configurations configuration-root])
        policy-root (:authority-semantics-policy/root configuration)
        policy (get-in snapshot [:authority-semantics-policies policy-root])
        semantics-root (:authority-semantics/root policy)
        semantics (get-in snapshot [:governed-authority-semantics semantics-root])
        configuration-root-fn (requiring-resolve
                               'resolver-sim.genesis/chain-configuration-root)
        ;; authority-semantics-policy depends on governed-authority-semantics,
        ;; which depends on this namespace; keep this runtime edge to avoid a
        ;; namespace cycle while retaining the CAS-bound verification below.
        policy-selection-fn (requiring-resolve
                             'resolver-sim.benchmark.authority-semantics-policy/verify-policy-selection)]
    (when (and (= envelope-v2-schema (:artifact/schema envelope))
               (configuration-head/valid-head-state? head-state)
               (= (:configuration-head/root envelope)
                  (configuration-head/head-state-root head-state))
               (= configuration-root (:configuration/head-root head-state))
               (= configuration-root (configuration-root-fn configuration))
               (= policy-root (:authority-semantics-policy/root configuration))
               (= semantics-root (:authority-semantics/root policy))
               (:valid? (policy-selection-fn policy semantics)))
      {:configuration/root configuration-root
       :policy/root policy-root
       :semantics/root semantics-root
       :semantics semantics})))

(defn evaluate-and-issue-finalizable-authority-fence!
  "Evaluate a V2 current-admission request from frozen store material and issue
   a store-owned finalization capability only for an `:authorised` report.
   The report root is recorded by the same CAS that issues the fence, so callers
   cannot turn an observation or a non-authorised report into authority."
  ([store basis authorisation]
   (evaluate-and-issue-finalizable-authority-fence! store basis authorisation nil))
  ([store basis authorisation authority-semantics]
   (evaluate-and-issue-finalizable-authority-fence! store basis authorisation authority-semantics nil))
  ([store basis authorisation authority-semantics authority-provenance]
   (let [initial-retained-semantics (when authority-semantics
                                      (retained-authority-semantics @(.state store)))]
     (if (and authority-semantics
              (not (and (= authority-semantics (:semantics initial-retained-semantics))
                        (= authority-provenance
                           {:chain-configuration/root (:configuration/root initial-retained-semantics)
                            :authority-semantics-policy/root (:policy/root initial-retained-semantics)}))))
       {:valid? false :reason :authority-semantics-provenance-invalid}
       (let [resolved (resolve-authority-material store basis)]
         (if-not (:resolved? resolved)
           {:valid? false :reason (:reason resolved)}
           (let [context (:context resolved)
                 material (:authenticated-material resolved)
                 ;; Configured C4 issuance is the ratified boundary. Legacy
                 ;; raw-input evaluation remains available only through callers
                 ;; that do not select configured authority semantics.
                 authorisation-validation (when authority-semantics
                                            (governed-authorisation/validate-authorisation authorisation))]
             (if (and authority-semantics (not (:valid? authorisation-validation)))
               {:valid? false :reason :governed-authority-authorisation-invalid
                :errors (:errors authorisation-validation)}
               (let [inputs {:authorisation authorisation
                             :review-round (:authority-material/review-round material)
                             :review-governance (:authority-material/review-governance material)
                             :position-time-index (:authority-material/position-time-index material)
                             :signer-key-set (:authority-material/signer-key-set material)}
                     evaluator-report (if authority-semantics
                                        ((requiring-resolve 'resolver-sim.benchmark.governed-authority-semantics/evaluate-authority-with-semantics)
                                         authority-semantics inputs)
                                        (evaluate-authority-with-frozen-material inputs))
                     ;; Never bind a configured fence to the legacy report root.
                     ;; The v1 projection preserves evaluator-established order.
                     report (if authority-semantics
                              (authority-report/build-report evaluator-report)
                              evaluator-report)
                     report-root (if authority-semantics
                                   (:three-member-authority-report/root report)
                                   (authority/authority-report-root report))
                     result {:valid? (= :authorised (:authority-status report))
                             :authority-report report
                             :authority-report-root report-root
                             :governance-root (:governance-root report)
                             :governed-review-round-hash
                             (get-in authorisation [:authorisation/review-round :review-round/hash])
                             :resolved-review-authority-context context}]
                 (if-not (:valid? result)
                   (assoc result :reason :authority-not-authorised)
                   (loop []
                     (let [current @(.state store)
                           state-root (:resolution/state-before-root context)
                           observation (:resolution-observation resolved)
                           fence-id (str (java.util.UUID/randomUUID))
                           predecessor-envelope (get-in current [:envelopes (:authority-state/root context)])
                           verification-basis (when authority-semantics
                                                (let [vb-result (build-report-verification-basis
                                                                 {:governed-authority-authorisation/root
                                                                  (:governed-authority-authorisation/root authorisation)
                                                                  :resolved-review-authority-context/root
                                                                  (:resolved-review-authority-context/root context)
                                                                  :authority-evaluation-basis/root
                                                                  (:authority-evaluation-basis/root (:evaluation-basis resolved))
                                                                  :predecessor-authoritative-state-envelope/root
                                                                  (:authoritative-state-envelope/root predecessor-envelope)
                                                                  :predecessor-configuration-head/root
                                                                  (:configuration-head/root predecessor-envelope)
                                                                  :chain-configuration/root (:chain-configuration/root authority-provenance)
                                                                  :authority-semantics-policy/root (:authority-semantics-policy/root authority-provenance)
                                                                  :authority-semantics/root (:governed-authority-semantics/root authority-semantics)
                                                                  :three-member-authority-report/root report-root})]
                                                  vb-result))
                           verification-basis-root (:governed-authority-report-verification-basis/root verification-basis)
                           record {:authority-state-envelope/root (:authority-state/root context)
                                   :execution/state-root state-root
                                   :publication/sequence (get-in current [:envelopes (:head current) :publication/sequence])
                                   :resolved-review-authority-context/root (:resolved-review-authority-context/root context)
                                   :resolution-basis/root (:resolution-basis/root basis)
                                   :review-round/root (:review-round/root context)
                                   :review-governance/root (:review-governance/root context)
                                   :position-time-basis/root (:position-time-basis/root context)
                                   :position-time-index/root (:position-time-index/root context)
                                   :signer-key-set/root (:signer-key-set/root material)
                                   :authority-evaluation-basis/root
                                   (get-in resolved [:evaluation-basis :authority-evaluation-basis/root])
                                   :authority-report/root report-root
                                   :authority-report-verification-basis/root verification-basis-root
                                   :authority-status :authorised
                                   :purpose :current-admission
                                   :authority-semantics/root (when authority-semantics (:governed-authority-semantics/root authority-semantics))
                                   :chain-configuration/root (:chain-configuration/root authority-provenance)
                                   :authority-semantics-policy/root (:authority-semantics-policy/root authority-provenance)
                                   :status :issued}]
                       (let [retained-semantics (when authority-semantics
                                                  (retained-authority-semantics current))
                             semantics-match?
                             (or (nil? authority-semantics)
                                 (and (= authority-semantics (:semantics retained-semantics))
                                      (= authority-provenance
                                         {:chain-configuration/root (:configuration/root retained-semantics)
                                          :authority-semantics-policy/root (:policy/root retained-semantics)})))]
                         (cond
                           (or (not= (:head current) (:authority-state-envelope/root observation))
                               (not= state-root (get-in current [:envelopes (:head current) :execution/state-root]))
                               (not= (:publication/sequence observation)
                                     (get-in current [:envelopes (:head current) :publication/sequence])))
                           {:valid? false :reason :state-not-at-required-head}

                           (not semantics-match?)
                           {:valid? false :reason :authority-semantics-provenance-invalid}

                           :else
                           (let [base-next (cond-> (assoc-in current [:issued-fences fence-id] record)
                                             authority-semantics
                                             (assoc-in [:governed-authority-authorisations
                                                        (:governed-authority-authorisation/root authorisation)] authorisation)

                                             authority-semantics
                                             (assoc-in [:three-member-authority-reports report-root] report)

                                             authority-semantics
                                             (assoc-in [:authority-evaluation-bases
                                                        (:authority-evaluation-basis/root (:evaluation-basis resolved))]
                                                       (:evaluation-basis resolved))

                                             authority-semantics
                                             (assoc-in [:resolved-review-authority-contexts
                                                        (:resolved-review-authority-context/root context)] context)

                                             authority-semantics
                                             (assoc-in [:governed-authority-report-verification-bases
                                                        verification-basis-root] verification-basis))
                                 next (if authority-semantics
                                        (assoc-in base-next
                                                  [:governed-authority-report-verification-bases
                                                   verification-basis-root]
                                                  verification-basis)
                                        base-next)]
                             (let [replay (when authority-semantics
                                            (verify-governed-authority-report-from-basis
                                             (AuthorityStateStore. (atom next))
                                             verification-basis-root))]
                               (cond
                                 (and authority-semantics (not (:valid? replay)))
                                 {:valid? false :reason :authority-report-replay-invalid
                                  :replay-reason (:reason replay)
                                  :replay replay}

                                 (compare-and-set! (.state store) current next)
                                 (assoc result :authority-fence {:fence/id fence-id}
                                        :authority-report-verification-basis/root verification-basis-root
                                        :authority-semantics/root (when authority-semantics (:governed-authority-semantics/root authority-semantics))
                                        :chain-configuration/root (:chain-configuration/root authority-provenance)
                                        :authority-semantics-policy/root (:authority-semantics-policy/root authority-provenance))

                                 :else (recur))))))))))))))))))

(defn- finalisation-receipt [snapshot record pre-envelope successor-envelope binding]
  (let [successor-configuration-root (:chain-configuration/root successor-envelope)
        successor-configuration (get-in snapshot [:chain-configurations successor-configuration-root])]
    (result-receipt/build-receipt
     {:pre-authoritative-state-envelope/root (:authority-state-envelope/root record)
      :post-authoritative-state-envelope/root (:authoritative-state-envelope/root successor-envelope)
      :transaction/state-before-root (:transaction/state-before-root binding)
      :transaction/state-after-root (:transaction/state-after-root binding)
      :authority-report/root (:authority-report/root record)
      :resolved-review-authority-context/root (:resolved-review-authority-context/root record)
      :governed-authority-transition-binding/root
      (:governed-authority-transition-binding/root binding)
      :pre-chain-configuration/root (:chain-configuration/root pre-envelope)
      :pre-authority-semantics-policy/root (:authority-semantics-policy/root record)
      :pre-governed-authority-semantics/root (:authority-semantics/root record)
      :successor-chain-configuration/root successor-configuration-root
      :successor-authority-semantics-policy/root
      (:authority-semantics-policy/root successor-configuration)
      :successor-governed-authority-semantics/root
      (get-in snapshot [:authority-semantics-policies
                        (:authority-semantics-policy/root successor-configuration)
                        :authority-semantics/root])})))

(defn finalise-under-authority-fence!
  "Atomically consume an issued fence with successor and authority binding.
    Exact retry returns the original terminal result; conflicting reuse rejects.
    The successor material passes the shared authenticated publication gate
    before the single authoritative CAS update."
  [store fence binding successor-envelope successor-material]
  (loop []
    (let [current @(.state store) fence-id (:fence/id fence) record (get-in current [:issued-fences fence-id])]
      (cond
        (nil? record) {:finalised? false :reason :unknown-fence}
        (= :consumed (:status record))
        (if (= (:transition-binding/root record) (:governed-authority-transition-binding/root binding))
          (:result record) {:finalised? false :reason :fence-already-consumed})
        (not (:valid? (resolution/validate-transition-binding binding))) {:finalised? false :reason :authority-transition-binding-invalid}
        (not= (:head current) (:authority-state-envelope/root record)) {:finalised? false :reason :state-not-at-required-head}
        (not= (:execution/state-root record) (:transaction/state-before-root binding)) {:finalised? false :reason :fence-pre-state-mismatch}
        (not= (:resolved-review-authority-context/root record) (:resolved-review-authority-context/root binding)) {:finalised? false :reason :authority-context-mismatch}
        (not= :authorised (:authority-status record)) {:finalised? false :reason :authority-report-not-authorised}
        (and (:authority-semantics/root record)
             (not (every? ref/valid-sha256-ref?
                          [(:authority-semantics/root record)
                           (:chain-configuration/root record)
                           (:authority-semantics-policy/root record)])))
        {:finalised? false :reason :authority-semantics-provenance-invalid}
        (not (ref/valid-sha256-ref? (:authority-report/root record))) {:finalised? false :reason :authority-report-binding-missing}
        :else (let [envelope (build-envelope successor-envelope)
                    predecessor-envelope (get-in current [:envelopes (:head current)])]
                (cond
                  (not= (:transaction/state-after-root binding) (:execution/state-root envelope))
                  {:finalised? false :reason :fence-post-state-mismatch}

                  (not= (:publication/predecessor-root envelope)
                        (:authority-state-envelope/root record))
                  {:finalised? false :reason :fence-predecessor-mismatch}

                  :else
                  (let [successor-material (require-authenticated-material! successor-material)
                        _ (when-not (= (:chain-instance-genesis/root envelope)
                                       (:chain-instance-genesis/root successor-material))
                            (throw (ex-info "successor material chain does not match successor envelope" {})))
                        root (:authoritative-state-envelope/root envelope)
                        receipt (finalisation-receipt current record predecessor-envelope envelope binding)
                        receipt-root (:governed-authority-result-receipt/root receipt)
                        result {:finalised? true :envelope envelope :authority-binding binding
                                :authority-report-root (:authority-report/root record)
                                :governed-authority-result-receipt receipt}
                        next (-> current (assoc :head root) (assoc-in [:envelopes root] envelope)
                                 (assoc-in [:by-state (:execution/state-root envelope)] root)
                                 (assoc-in [:material (:execution/state-root envelope)] successor-material)
                                 (assoc-in [:authority-bindings root] binding)
                                 (assoc-in [:governed-authority-result-receipts receipt-root] receipt)
                                 (assoc-in [:governed-authority-result-receipt-by-binding
                                            (:governed-authority-transition-binding/root binding)] receipt-root)
                                 (assoc-in [:issued-fences fence-id]
                                           (assoc record :status :consumed
                                                  :transition-binding/root (:governed-authority-transition-binding/root binding)
                                                  :successor-envelope/root root
                                                  :governed-authority-result-receipt/root receipt-root
                                                  :result result)))]
                    (if (compare-and-set! (.state store) current next) result (recur)))))))))

(defn finalise-under-authority-fence-v2!
  "D4 finalization under a C4f-issued fence. The current V2 E1/H1/C1/P1/S1
   lineage is retained by the store: callers provide only successor execution
   envelope/material candidates, never replacement configuration semantics.
   E2 is rebuilt with the exact retained H1 and must retain its active C1."
  [store fence binding successor-envelope successor-material]
  (loop []
    (let [current @(.state store)
          fence-id (:fence/id fence)
          record (get-in current [:issued-fences fence-id])
          current-envelope (get-in current [:envelopes (:head current)])
          head-state (get-in current [:configuration-head-states
                                      (:configuration-head/root current-envelope)])
          configuration-root (:chain-configuration/root current-envelope)
          configuration (get-in current [:chain-configurations configuration-root])
          policy-root (:authority-semantics-policy/root configuration)
          policy (get-in current [:authority-semantics-policies policy-root])
          semantics-root (:authority-semantics/root policy)
          semantics (get-in current [:governed-authority-semantics semantics-root])]
      (cond
        (nil? record) {:finalised? false :reason :unknown-fence}
        (= :consumed (:status record))
        (if (= (:transition-binding/root record)
               (:governed-authority-transition-binding/root binding))
          (:result record)
          {:finalised? false :reason :fence-already-consumed})
        (not (and (= envelope-v2-schema (:artifact/schema current-envelope))
                  (configuration-head/valid-head-state? head-state)
                  (= (:configuration-head/root current-envelope)
                     (configuration-head/head-state-root head-state))
                  (= configuration-root (:configuration/head-root head-state))
                  configuration policy semantics
                  (= policy-root (:authority-semantics-policy/root configuration))
                  (= semantics-root (:authority-semantics/root policy))
                  (= configuration-root (:chain-configuration/root record))
                  (= policy-root (:authority-semantics-policy/root record))
                  (= semantics-root (:authority-semantics/root record))))
        {:finalised? false :reason :authority-semantics-provenance-invalid}
        (not (:valid? (resolution/validate-transition-binding binding)))
        {:finalised? false :reason :authority-transition-binding-invalid}
        (not= (:head current) (:authority-state-envelope/root record))
        {:finalised? false :reason :state-not-at-required-head}
        (not= (:execution/state-root record) (:transaction/state-before-root binding))
        {:finalised? false :reason :fence-pre-state-mismatch}
        (not= (:resolved-review-authority-context/root record)
              (:resolved-review-authority-context/root binding))
        {:finalised? false :reason :authority-context-mismatch}
        (not= :authorised (:authority-status record))
        {:finalised? false :reason :authority-report-not-authorised}
        (not (ref/valid-sha256-ref? (:authority-report/root record)))
        {:finalised? false :reason :authority-report-binding-missing}
        (not= envelope-v2-schema (:artifact/schema successor-envelope))
        {:finalised? false :reason :authoritative-v2-successor-required}
        (not= configuration-root (:chain-configuration/root successor-envelope))
        {:finalised? false :reason :successor-configuration-mismatch}
        :else
        (let [envelope (build-envelope-v2 successor-envelope head-state)]
          (cond
            (not= (:transaction/state-after-root binding) (:execution/state-root envelope))
            {:finalised? false :reason :fence-post-state-mismatch}
            (not= (:publication/predecessor-root envelope)
                  (:authority-state-envelope/root record))
            {:finalised? false :reason :fence-predecessor-mismatch}
            :else
            (let [successor-material (require-authenticated-material! successor-material)]
              (cond
                (not= configuration-root (:chain-configuration/root successor-material))
                {:finalised? false :reason :successor-configuration-mismatch}
                (not= (:chain-instance-genesis/root envelope)
                      (:chain-instance-genesis/root successor-material))
                {:finalised? false :reason :successor-material-chain-mismatch}
                :else
                (let [root (:authoritative-state-envelope/root envelope)
                      receipt (finalisation-receipt current record current-envelope envelope binding)
                      receipt-root (:governed-authority-result-receipt/root receipt)
                      result {:finalised? true :envelope envelope :authority-binding binding
                              :authority-report-root (:authority-report/root record)
                              :governed-authority-result-receipt receipt}
                      next (-> current
                               (assoc :head root)
                               (assoc-in [:envelopes root] envelope)
                               (assoc-in [:by-state (:execution/state-root envelope)] root)
                               (assoc-in [:material (:execution/state-root envelope)] successor-material)
                               (assoc-in [:configuration-head-states (:configuration-head/root envelope)] head-state)
                               (assoc-in [:authority-bindings root] binding)
                               (assoc-in [:governed-authority-result-receipts receipt-root] receipt)
                               (assoc-in [:governed-authority-result-receipt-by-binding
                                          (:governed-authority-transition-binding/root binding)] receipt-root)
                               (assoc-in [:issued-fences fence-id]
                                         (assoc record :status :consumed
                                                :transition-binding/root (:governed-authority-transition-binding/root binding)
                                                :successor-envelope/root root
                                                :governed-authority-result-receipt/root receipt-root
                                                :result result)))]
                  (if (compare-and-set! (.state store) current next)
                    result
                    (recur)))))))))))
