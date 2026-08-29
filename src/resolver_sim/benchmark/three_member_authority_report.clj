(ns resolver-sim.benchmark.three-member-authority-report
  "Closed post-ratification projection of the final return value of
  `evaluate-three-member-authority`.  This is deliberately distinct from the
  legacy unversioned authority-report root: it is a semantic report artifact,
  not a storage-byte checksum."
  (:require [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "three-member-authority-report.v1")
(def ^:const domain :three-member-authority-report-v1)

(def report-fields
  #{:artifact/schema :constitution-status :constitution :governance-root
    :governance-fresh? :constituted-member-count :required-threshold
    :counted-support :outcome-root :outcome-source :authoritative-target-root
    :decision-scope/root :policy-conforming? :identity-separate?
    :valid-supporting-positions :valid-dissenting-positions
    :valid-qualifying-positions :effective-dissent-count :absent-members
    :invalid-positions :invalid-position-reasons :equivocating-members
    :unknown-members :re-scoped-positions :duplicate-seat-positions
    :equivocation-policy-applied :authority-status :authority/reasons
    :three-member-authority-report/root})

(def ^:private position-common-fields
  #{:position/schema :researcher/id :authorisation/request-root
    :review-round/hash :decision :decision/hash :signature})
(def ^:private position-v1-fields position-common-fields)
(def ^:private position-v2-fields
  (conj position-common-fields :authorisation/id :outcome/root))
(def ^:private signature-fields #{:algorithm :value :signed-at})
(def ^:private invalid-reasons
  #{"integrity-or-signature-failed"
    :resolver-sim.assurance.three-member-authority/missing-signing-key-id
    :resolver-sim.assurance.three-member-authority/governed-signing-key-invalid})
(def ^:private position-schemas
  #{"three-member-authority-report-position.v1"
    "three-member-authority-report-position.v2"})

(defn- exact-keys? [m ks]
  (and (map? m) (= ks (set (keys m)))))

(defn- position-schema [position]
  (case (rfa/classify-decision-version position)
    :v2-complete-outcome "three-member-authority-report-position.v2"
    "three-member-authority-report-position.v1"))

(defn project-position
  "Project one evaluator-visible decision position into a closed report
  position.  Legacy extra keys never cross this post-ratification boundary."
  [position]
  (let [schema* (position-schema position)
        base (if (= schema* "three-member-authority-report-position.v2")
               position-v2-fields position-v1-fields)
        fields (cond-> base
                 (contains? position :dissent/reason) (conj :dissent/reason)
                 (contains? position :signing-key/id) (conj :signing-key/id))]
    (assoc (select-keys position (disj fields :position/schema))
           :position/schema schema*)))

(defn- project-constitution [constitution]
  (when constitution
    {:constitution-status (:constitution-status constitution)
     :policy (when-let [policy (:policy constitution)]
               {:policy/id (:policy/id policy)
                :member-count (:member-count policy)
                :threshold (:threshold policy)
                :required-roles (vec (sort-by pr-str (:required-roles policy)))
                :role-cardinality (:role-cardinality policy)
                :equivocation-policy (:equivocation-policy policy)})
     :governance-root (:governance-root constitution)
     :member-ids (vec (:member-ids constitution))
     :principal-ids (vec (:principal-ids constitution))
     :eligible-key-sets (mapv #(vec (sort-by pr-str %)) (:eligible-key-sets constitution))
     :independence (mapv #(select-keys % #{:principals :status}) (:independence constitution))
     :errors (vec (:errors constitution))}))

(defn project-evaluator-report
  "The sole migration seam from the existing evaluator return value to the
  closed v1 report. It preserves evaluator vector order; it only projects
  nested positions and canonicalizes Clojure sets emitted by constitution
  evaluation into deterministic vectors."
  [report]
  (let [project-positions #(mapv project-position (or % []))]
    {:artifact/schema schema
     :constitution-status (:constitution-status report)
     :constitution (project-constitution (:constitution report))
     :governance-root (:governance-root report)
     :governance-fresh? (:governance-fresh? report)
     :constituted-member-count (:constituted-member-count report)
     :required-threshold (:required-threshold report)
     :counted-support (:counted-support report)
     :outcome-root (:outcome-root report)
     :outcome-source (:outcome-source report)
     :authoritative-target-root (:authoritative-target-root report)
     :decision-scope/root (:decision-scope/root report)
     :policy-conforming? (:policy-conforming? report)
     :identity-separate? (:identity-separate? report)
     :valid-supporting-positions (project-positions (:valid-supporting-positions report))
     :valid-dissenting-positions (project-positions (:valid-dissenting-positions report))
     :valid-qualifying-positions (project-positions (:valid-qualifying-positions report))
     :effective-dissent-count (:effective-dissent-count report)
     :absent-members (vec (:absent-members report))
     :invalid-positions (project-positions (:invalid-positions report))
     :invalid-position-reasons
     (mapv (fn [{:keys [position reason]}]
             {:position (project-position position) :reason reason})
           (:invalid-position-reasons report))
     :equivocating-members
     (mapv (fn [{:keys [equivocation/key member/id incompatible-positions incompatibility-reasons]}]
             {:equivocation/key key :member/id id
              :incompatible-positions (project-positions incompatible-positions)
              :incompatibility-reasons (vec incompatibility-reasons)})
           (:equivocating-members report))
     :unknown-members (project-positions (:unknown-members report))
     :re-scoped-positions (project-positions (:re-scoped-positions report))
     :duplicate-seat-positions (project-positions (:duplicate-seat-positions report))
     :equivocation-policy-applied (:equivocation-policy-applied report)
     :authority-status (:authority-status report)
     :authority/reasons (vec (:authority/reasons report))}))

(defn report-root [report]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc report :three-member-authority-report/root)))))

(defn- valid-signature? [signature]
  (and (exact-keys? signature signature-fields)
       (= :ed25519 (:algorithm signature))
       (string? (:value signature)) (not-empty (:value signature))
       (string? (:signed-at signature)) (not-empty (:signed-at signature))))

(defn- valid-position? [position]
  (let [schema* (:position/schema position)
        base (if (= schema* "three-member-authority-report-position.v2")
               position-v2-fields position-v1-fields)
        fields (cond-> base
                 (contains? position :dissent/reason) (conj :dissent/reason)
                 (contains? position :signing-key/id) (conj :signing-key/id))]
    (and (contains? position-schemas schema*)
         (exact-keys? position fields)
         (string? (:researcher/id position)) (not-empty (:researcher/id position))
         (ref/valid-sha256-ref? (:authorisation/request-root position))
         (ref/valid-sha256-ref? (:review-round/hash position))
         (contains? #{:approve :dissent} (:decision position))
         (ref/valid-sha256-ref? (:decision/hash position))
         (valid-signature? (:signature position))
         (if (= :dissent (:decision position))
           (and (string? (:dissent/reason position)) (not-empty (:dissent/reason position)))
           (not (contains? position :dissent/reason)))
         (if (= schema* "three-member-authority-report-position.v2")
           (and (some? (:authorisation/id position))
                (ref/valid-sha256-ref? (:outcome/root position))
                (or (not (contains? position :signing-key/id))
                    (some? (:signing-key/id position))))
           true))))

(defn- valid-constitution? [constitution]
  (or (nil? constitution)
      (and (exact-keys? constitution #{:constitution-status :policy :governance-root
                                       :member-ids :principal-ids :eligible-key-sets
                                       :independence :errors})
           (contains? #{:valid :invalid :unresolved} (:constitution-status constitution))
           (or (nil? (:policy constitution))
               (exact-keys? (:policy constitution)
                            #{:policy/id :member-count :threshold :required-roles
                              :role-cardinality :equivocation-policy}))
           (ref/valid-sha256-ref? (:governance-root constitution))
           (every? some? (:member-ids constitution))
           (every? some? (:principal-ids constitution))
           (every? vector? (:eligible-key-sets constitution))
           (every? #(and (exact-keys? % #{:principals :status})
                         (vector? (:principals %))
                         (contains? #{:independent :not-independent :independence-unresolved}
                                    (:status %)))
                   (:independence constitution))
           (every? string? (:errors constitution)))))

(defn validate-report
  "Pure closed-schema validation. This validates report representation and its
  semantic root; replaying evaluator semantics remains a later verifier step."
  [report]
  (let [positions (concat (:valid-supporting-positions report)
                          (:valid-dissenting-positions report)
                          (:valid-qualifying-positions report)
                          (:invalid-positions report) (:unknown-members report)
                          (:re-scoped-positions report) (:duplicate-seat-positions report)
                          (mapcat :incompatible-positions (:equivocating-members report))
                          (map :position (:invalid-position-reasons report)))
        errors (cond-> []
                 (not (map? report)) (conj "report must be a map")
                 (and (map? report) (not= report-fields (set (keys report))))
                 (conj "report has missing or unknown keys")
                 (and (map? report) (not= schema (:artifact/schema report)))
                 (conj "invalid report schema")
                 (not (contains? #{:valid :invalid :unresolved :legacy-unbound}
                                 (:constitution-status report)))
                 (conj "invalid constitution status")
                 (not (valid-constitution? (:constitution report)))
                 (conj "invalid constitution report")
                 (not (contains? #{:authorised :not-authorised} (:authority-status report)))
                 (conj "invalid authority status")
                 (not (contains? #{:authoritative-target :target-outcome-unavailable}
                                 (:outcome-source report)))
                 (conj "invalid outcome source")
                 (not (contains? #{:invalid-seat :count-as-dissent :fail-certificate}
                                 (:equivocation-policy-applied report)))
                 (conj "invalid equivocation policy")
                 (not (every? integer? [(:constituted-member-count report)
                                        (:required-threshold report) (:counted-support report)
                                        (:effective-dissent-count report)]))
                 (conj "invalid report count")
                 (not (every? valid-position? positions))
                 (conj "invalid closed report position")
                 (not (every? #(and (exact-keys? % #{:position :reason})
                                    (valid-position? (:position %))
                                    (contains? invalid-reasons (:reason %)))
                              (:invalid-position-reasons report)))
                 (conj "invalid invalid-position reason")
                 (not (every? #(and (exact-keys? % #{:equivocation/key :member/id
                                                     :incompatible-positions :incompatibility-reasons})
                                    (vector? (:equivocation/key %))
                                    (some? (:member/id %))
                                    (vector? (:incompatible-positions %))
                                    (every? valid-position? (:incompatible-positions %))
                                    (every? #{:decision-divergence :distinct-outcome-roots
                                              :distinct-dissent-reasons :distinct-position}
                                            (:incompatibility-reasons %)))
                              (:equivocating-members report)))
                 (conj "invalid equivocation report")
                 (not= (:three-member-authority-report/root report) (report-root report))
                 (conj "report root mismatch"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-report
  "Seal an already-projected closed report, or project an evaluator result.
  Arbitrary maps cannot enter the artifact because validation is closed."
  [evaluator-report]
  (let [projection (if (= schema (:artifact/schema evaluator-report))
                     (dissoc evaluator-report :three-member-authority-report/root)
                     (project-evaluator-report evaluator-report))
        report (assoc projection :three-member-authority-report/root (report-root projection))
        result (validate-report report)]
    (when-not (:valid? result)
      (throw (ex-info "three-member authority report is invalid" result)))
    report))

(defn verify-report [report]
  (:valid? (validate-report report)))
