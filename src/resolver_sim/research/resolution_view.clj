(ns resolver-sim.research.resolution-view
  "Read-only presentation contract for independently verified research cases.

   Domain adapters provide results from their own verifiers. This namespace
   validates and roots the projection; it does not run domain methods, upgrade
   unknown claims, reconstruct ratification, or grant authority."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]))

(def schema-version "prf/research-resolution-view.v1")
(def claim-statuses #{:supported-within-scope :counterexample-found
                      :not-established :invalid})
(def method-statuses #{:verified :unverified :invalid})
(def method-source-statuses #{:pinned :unverified})
(def reproduction-statuses #{:independent :same-implementation :absent :not-comparable})
(def ratification-statuses #{:ratified :ratified-with-dissent :contested
                             :rejected :absent :unverified})
(def view-fields
  #{:schema-version :resolution-view/subject-root :resolution-view/scope-root
    :resolution-view/method-root :resolution-view/evidence-root
    :resolution-view/claim-status :resolution-view/method-status
    :resolution-view/method-source-status :resolution-view/reproduction-status
    :resolution-view/ratification-status :resolution-view/operational-authority?
    :resolution-view/limitations :resolution-view/root})

(defn view-root [view]
  (reference/sha256-ref
   (canonical/domain-hash :prf-research-resolution-view-v1
                          (dissoc view :resolution-view/root))))

(defn validate-view [view]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version view))
                 (conj :unsupported-schema)
                 (not= view-fields (set (keys view)))
                 (conj :invalid-fields)
                 (not-every? reference/valid-sha256-ref?
                             (map view [:resolution-view/subject-root
                                        :resolution-view/scope-root
                                        :resolution-view/method-root
                                        :resolution-view/evidence-root]))
                 (conj :invalid-root-reference)
                 (not (contains? claim-statuses (:resolution-view/claim-status view)))
                 (conj :invalid-claim-status)
                 (not (contains? method-statuses (:resolution-view/method-status view)))
                 (conj :invalid-method-status)
                 (not (contains? method-source-statuses
                                 (:resolution-view/method-source-status view)))
                 (conj :invalid-method-source-status)
                 (not (contains? reproduction-statuses (:resolution-view/reproduction-status view)))
                 (conj :invalid-reproduction-status)
                 (not (contains? ratification-statuses (:resolution-view/ratification-status view)))
                 (conj :invalid-ratification-status)
                 (not= false (:resolution-view/operational-authority? view))
                 (conj :research-view-cannot-issue-operational-authority)
                 (not (and (vector? (:resolution-view/limitations view))
                           (every? string? (:resolution-view/limitations view))))
                 (conj :invalid-limitations)
                 (and (:resolution-view/root view)
                      (not= (:resolution-view/root view) (view-root view)))
                 (conj :root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-view
  "Build a read-only view from a domain verifier's result.

   The adapter must pass its actual claim, scope, method and evidence roots.
   Ratification is an independent dimension and never changes claim status."
  [{:keys [subject-root scope-root method-root evidence-root claim-status
           method-status method-source-status reproduction-status
           ratification-status limitations]}]
  (let [base {:schema-version schema-version
              :resolution-view/subject-root subject-root
              :resolution-view/scope-root scope-root
              :resolution-view/method-root method-root
              :resolution-view/evidence-root evidence-root
              :resolution-view/claim-status claim-status
              :resolution-view/method-status method-status
              :resolution-view/method-source-status (or method-source-status :unverified)
              :resolution-view/reproduction-status reproduction-status
              :resolution-view/ratification-status (or ratification-status :absent)
              :resolution-view/operational-authority? false
              :resolution-view/limitations
              (mapv #(if (keyword? %) (name %) (str %)) limitations)}
        view (assoc base :resolution-view/root (view-root base))
        validation (validate-view view)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid research resolution view" validation)))
    view))
