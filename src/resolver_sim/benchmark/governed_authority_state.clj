(ns resolver-sim.benchmark.governed-authority-state
  "Stage B authenticated state view for governed-authority resolution.

  The store is an operational dependency and is deliberately not committed into
  resolution artifacts. Its atom is the publication boundary for an authority
  envelope and the state-addressed material it authenticates."
  (:require [clojure.set :as set]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-governance-evidence :as evidence]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
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
    ((ns-resolve (find-ns 'resolver-sim.assurance.three-member-authority)
                 'evaluate-governed-authority)
     :authorisation authorisation
     :review-round review-round
     :governance review-governance
     :governance-current? (fn [_ _] true)
     :signature-valid? signature-valid?)))

(def ^:const evaluation-basis-schema "governed-authority-evaluation-basis.v1")

(def ^:const evaluation-basis-fields
  #{:resolved-review-authority-context/root :review-round/root
    :review-governance/root :position-time-basis/root
    :position-time-index/root :signer-key-set/root})

(defn evaluation-basis
  "Join the resolved semantic authority context with the authenticated
    verification/key basis. The join is versioned and domain-separated so
    historical evidence can name the exact evaluation basis that justified a
    decision instead of relying on a transient store registry."
  [parts]
  (let [base (select-keys parts [:resolved-review-authority-context/root
                                 :review-round/root :review-governance/root
                                 :position-time-basis/root :position-time-index/root
                                 :signer-key-set/root])]
    (when-not (and (= (count base) (count evaluation-basis-fields))
                   (every? #(ref/valid-sha256-ref? (get base %))
                           evaluation-basis-fields))
      (throw (ex-info "governed-authority-evaluation-basis is invalid"
                      {:missing (set/difference evaluation-basis-fields
                                                (set (keys base)))})))
    (let [schema-base (assoc base :artifact/schema evaluation-basis-schema)]
      (assoc schema-base :authority-evaluation-basis/root
             (ref/sha256-ref
              (hc/domain-hash :governed-authority-evaluation-basis-v1
                              (hc/project-canonical-safe schema-base)))))))

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

(defn- require-authenticated-material!
  "The single publication gate shared by initial construction and every
   successor path: freeze, then prove the closed authenticated shape and its
   recomputed roots before anything may enter the store."
  [material]
  (let [material (freeze-material material)]
    (when-not (authenticated-material? material)
      (throw (ex-info "authority material is not rooted and authenticated" {})))
    material))

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

(defn evaluate-and-issue-finalizable-authority-fence!
  "Evaluate a V2 current-admission request from frozen store material and issue
   a store-owned finalization capability only for an `:authorised` report.
   The report root is recorded by the same CAS that issues the fence, so callers
   cannot turn an observation or a non-authorised report into authority."
  [store basis authorisation]
  (let [resolved (resolve-authority-material store basis)]
    (if-not (:resolved? resolved)
      {:valid? false :reason (:reason resolved)}
      (let [context (:context resolved)
            material (:authenticated-material resolved)
            report (evaluate-authority-with-frozen-material
                    {:authorisation authorisation
                     :review-round (:authority-material/review-round material)
                     :review-governance (:authority-material/review-governance material)
                     :position-time-index (:authority-material/position-time-index material)
                     :signer-key-set (:authority-material/signer-key-set material)})
            report-root (authority/authority-report-root report)
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
                          :authority-status :authorised
                          :purpose :current-admission
                          :status :issued}]
              (if (or (not= (:head current) (:authority-state-envelope/root observation))
                      (not= state-root (get-in current [:envelopes (:head current) :execution/state-root]))
                      (not= (:publication/sequence observation)
                            (get-in current [:envelopes (:head current) :publication/sequence])))
                {:valid? false :reason :state-not-at-required-head}
                (let [next (assoc-in current [:issued-fences fence-id] record)]
                  (if (compare-and-set! (.state store) current next)
                    (assoc result :authority-fence {:fence/id fence-id})
                    (recur)))))))))))

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
        (not (ref/valid-sha256-ref? (:authority-report/root record))) {:finalised? false :reason :authority-report-binding-missing}
        :else (let [envelope (build-envelope successor-envelope)
                    successor-material (require-authenticated-material! successor-material)
                    _ (when-not (= (:chain-instance-genesis/root envelope)
                                   (:chain-instance-genesis/root successor-material))
                        (throw (ex-info "successor material chain does not match successor envelope" {})))
                    root (:authoritative-state-envelope/root envelope)
                    result {:finalised? true :envelope envelope :authority-binding binding
                            :authority-report-root (:authority-report/root record)}
                    next (-> current (assoc :head root) (assoc-in [:envelopes root] envelope)
                             (assoc-in [:by-state (:execution/state-root envelope)] root)
                             (assoc-in [:material (:execution/state-root envelope)] successor-material)
                             (assoc-in [:authority-bindings root] binding)
                             (assoc-in [:issued-fences fence-id] (assoc record :status :consumed :transition-binding/root (:governed-authority-transition-binding/root binding) :successor-envelope/root root :result result)))]
                (if (compare-and-set! (.state store) current next) result (recur)))))))
