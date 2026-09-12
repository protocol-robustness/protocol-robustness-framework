(ns resolver-sim.related-claims
  "Protocol-neutral related-claim relationship commitments and acceptance checks."
  (:require [resolver-sim.hash.canonical :as hash]
            [resolver-sim.workflow-group :as wg]))

(def ^:const related-claims-domain "related-claims.v1")
(def ^:const related-claims-domain-v2 "related-claims.v2")
(def ^:const related-claims-domain-v3 "related-claims.v3")
(def ^:const related-claims-domain-v4 "related-claims.v4")

(def ^:const related-claims-version 1)
(def ^:const related-claims-version-v2 2)
(def ^:const related-claims-version-v3 3)
(def ^:const related-claims-version-v4 4)

(def ^:const default-semantics #{:audit-only})
(def ^:const shared-resolution-semantics #{:shared-resolution})

(defn- canonical-members [members]
  (vec (sort-by (juxt :workflow/id :claim/kind)
                (for [member members]
                  (select-keys member [:claim/kind :workflow/id :claim/scope-hash])))))

(defn related-claims-hash-v1 [members]
  (hash/domain-hash related-claims-domain (canonical-members members)))

(defn related-claims-hash-v2 [members creator-provenance]
  (hash/domain-hash related-claims-domain-v2
                    {:related-claims/schema-version "related-claims.v2"
                     :relationship/members (canonical-members members)
                     :relationship/creator-provenance (or creator-provenance {})}))

(defn related-claims-hash-v3 [members creator-provenance semantics]
  (hash/domain-hash related-claims-domain-v3
                    {:related-claims/schema-version "related-claims.v3"
                     :relationship/members (canonical-members members)
                     :relationship/semantics (vec (sort semantics))
                     :relationship/creator-provenance (or creator-provenance {})}))

(defn related-claims-hash-v4
  [members creator-provenance semantics incident-root shared-evidence-root resolution-policy-root]
  (hash/domain-hash related-claims-domain-v4
                    {:related-claims/schema-version "related-claims.v4"
                     :relationship/members (canonical-members members)
                     :relationship/semantics (vec (sort semantics))
                     :relationship/creator-provenance (or creator-provenance {})
                     :incident/root incident-root
                     :shared-evidence/root shared-evidence-root
                     :resolution-policy/root resolution-policy-root}))

(defn related-claims-hash
  ([members creator-provenance]
   (related-claims-hash-v3 members creator-provenance default-semantics))
  ([members creator-provenance semantics]
   (related-claims-hash-v3 members creator-provenance semantics)))

(defn verify-related-claims-hash [relationship]
  (let [version (:related-claims/version relationship)
        expected (case version
                   1 (related-claims-hash-v1 (:relationship/members relationship))
                   2 (related-claims-hash-v2 (:relationship/members relationship)
                                             (:relationship/creator-provenance relationship))
                   3 (related-claims-hash-v3 (:relationship/members relationship)
                                             (:relationship/creator-provenance relationship)
                                             (:relationship/semantics relationship))
                   4 (related-claims-hash-v4 (:relationship/members relationship)
                                             (:relationship/creator-provenance relationship)
                                             (:relationship/semantics relationship)
                                             (:incident/root relationship)
                                             (:shared-evidence/root relationship)
                                             (:resolution-policy/root relationship))
                   nil)
        reasons (cond-> #{}
                  (not (map? relationship)) (conj :invalid-relationship)
                  (not (contains? #{related-claims-version related-claims-version-v2
                                    related-claims-version-v3 related-claims-version-v4}
                                  version))
                  (conj :unsupported-relationship-version)
                  (and (= related-claims-version-v3 version)
                       (not= default-semantics (:relationship/semantics relationship)))
                  (conj :unsupported-semantics)
                  (and (= related-claims-version-v4 version)
                       (not= shared-resolution-semantics (:relationship/semantics relationship)))
                  (conj :unsupported-semantics)
                  (and (= related-claims-version-v4 version)
                       (not (every? string? [(:incident/root relationship)
                                             (:shared-evidence/root relationship)
                                             (:resolution-policy/root relationship)])))
                  (conj :shared-resolution-basis-invalid)
                  (and expected (not= (:relationship/hash relationship) expected))
                  (conj :relationship-hash-mismatch))]
    {:valid? (empty? reasons) :reasons reasons}))

(defn related-claims-member-hash [member]
  (wg/workflow-group-member-hash
   (wg/workflow-group-member (:claim/kind member) (:workflow/id member))))

(defn relationship-member? [relationship member]
  (wg/workflow-group-member?
   (map (fn [candidate]
          (wg/workflow-group-member (:claim/kind candidate) (:workflow/id candidate)))
        (:relationship/members relationship))
   (wg/workflow-group-member (:claim/kind member) (:workflow/id member))))

(defn related-claim-member? [relationship claim]
  (and (:valid? (verify-related-claims-hash relationship))
       (relationship-member? relationship claim)))

(defn related-claims-required-members [relationship]
  (if (:valid? (verify-related-claims-hash relationship))
    (vec (sort-by (juxt :workflow/id :claim/kind) (:relationship/members relationship)))
    []))

(defn- result-member [result]
  (select-keys result [:claim/kind :workflow/id]))

(defn related-claims-consistency [relationship member-results]
  (let [required (set (map related-claims-member-hash
                           (related-claims-required-members relationship)))
        actual (mapv result-member member-results)
        actual-hashes (mapv related-claims-member-hash actual)
        basis (select-keys relationship [:incident/root :shared-evidence/root
                                         :resolution-policy/root])
        conflicts (vec
                   (concat
                    (for [result member-results
                          :when (not (contains? required
                                                (related-claims-member-hash (result-member result))))]
                      {:conflict/class :member-not-required
                       :claim/member-hash (related-claims-member-hash (result-member result))})
                    (for [[member-hash n] (frequencies actual-hashes) :when (> n 1)]
                      {:conflict/class :duplicate-member-result :claim/member-hash member-hash})
                    (for [result member-results :when (not= :accepted (:resolution/status result))]
                      {:conflict/class :member-result-not-accepted
                       :claim/member-hash (related-claims-member-hash (result-member result))})
                    (for [result member-results
                          :when (not= basis (select-keys result (keys basis)))]
                      {:conflict/class :member-basis-mismatch
                       :claim/member-hash (related-claims-member-hash (result-member result))})))]
    {:consistent? (empty? conflicts) :conflicts conflicts}))

(defn shared-evidence-valid? [relationship resolve-artifact]
  (let [root (:shared-evidence/root relationship)
        evidence (when (fn? resolve-artifact) (resolve-artifact root))]
    (and (string? root) (map? evidence) (= root (:evidence/hash evidence)))))

(defn claim-acceptance [{:keys [relationship claim member-results resolve-artifact]}]
  (let [relationship-valid? (:valid? (verify-related-claims-hash relationship))
        member-hash (when (map? claim) (related-claims-member-hash claim))
        member? (and relationship-valid? (relationship-member? relationship claim))
        semantics (:relationship/semantics relationship)
        base {:relationship/root (:relationship/hash relationship)
              :claim/member-hash member-hash
              :basis (select-keys relationship [:incident/root :shared-evidence/root
                                                :resolution-policy/root])
              :group-resolution/root (:group-resolution/root relationship)}]
    (cond
      (not relationship-valid?) (assoc base :accepted? false :acceptance/status :invalid
                                       :conflicts [{:conflict/class :relationship-invalid}])
      (not member?) (assoc base :accepted? false :acceptance/status :rejected
                           :conflicts [{:conflict/class :claim-not-member}])
      (= default-semantics semantics)
      (assoc base :accepted? true :acceptance/status :valid
             :acceptance/scope :membership-only :coupling :none
             :required-member-results [] :missing-members [] :conflicts [])
      (not= shared-resolution-semantics semantics)
      (assoc base :accepted? false :acceptance/status :invalid
             :conflicts [{:conflict/class :unsupported-semantics}])
      :else
      (let [required (related-claims-required-members relationship)
            present (set (map (comp related-claims-member-hash result-member) member-results))
            missing (vec (remove #(contains? present (related-claims-member-hash %)) required))
            consistency (related-claims-consistency relationship member-results)
            evidence-valid? (shared-evidence-valid? relationship resolve-artifact)
            conflicts (cond-> (:conflicts consistency)
                        (not evidence-valid?) (conj {:conflict/class :shared-evidence-invalid}))
            accepted? (and (empty? missing) (empty? conflicts))]
        (assoc base :accepted? accepted?
               :acceptance/status (cond accepted? :accepted
                                        (seq conflicts) :rejected
                                        :else :incomplete)
               :acceptance/scope :coupled-resolution :coupling :shared-resolution
               :required-member-results required :missing-members missing
               :conflicts conflicts)))))
