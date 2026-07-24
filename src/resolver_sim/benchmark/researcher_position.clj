(ns resolver-sim.benchmark.researcher-position
  "Researcher position: a researcher's assessment of a benchmark model.
   
   The position is decomposed into model-component-level assessments,
   not a single approval or rejection. A researcher may challenge a
   specific model component (e.g. authority rules) while accepting
   others (e.g. state model).
   
   Every dimension supports three absent-statuses:
     not-reviewed            — the researcher did not assess it
     insufficient-information — attempted assessment but evidence was inadequate
     not-applicable          — the dimension does not apply to this benchmark
   
   Component disagreements may reference the exact model element:
     {:dimension :model-authority
      :status :inadequate
      :targets [{:kind :authority-policy
                 :id :position/current-amount-precedence
                 :component-hash \"sha256:...\"}]
      :rationale \"...\"}
   
   The position is submitted after the researcher has inspected their
   own report, the other two reports, divergences and model coverage.
   It is NOT required to complete a run."
  (:require [clojure.set]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "researcher-position.v1")

(def absent-statuses
  "Status values indicating non-assessment."
  #{:not-reviewed :insufficient-information :not-applicable})

(def dimension-statuses
  "Controlled vocabulary for each position dimension.
   Every dimension supports the three absent-statuses plus dimension-specific values."
  {:reproduction   (into absent-statuses #{:reproduced :unable-to-reproduce})
   :model-state    (into absent-statuses #{:adequate :incomplete :inadequate})
   :model-transitions (into absent-statuses #{:adequate :incomplete :inadequate})
   :model-authority (into absent-statuses #{:adequate :incomplete :contested})
   :model-adversary (into absent-statuses #{:adequate :incomplete :omitted})
   :model-parameters (into absent-statuses #{:adequate :insufficient-coverage})
   :model-cases   (into absent-statuses #{:adequate :insufficient})
   :incentives-participants (into absent-statuses #{:adequate :incomplete})
   :incentives-strategies (into absent-statuses #{:adequate :incomplete :omitted})
   :incentives-coalitions (into absent-statuses #{:adequate :omitted})
   :evidence      (into absent-statuses #{:sufficient :insufficient})
   :claims        (into absent-statuses #{:supported :overstated :not-supported})
   :publication   (into absent-statuses #{:publish :publish-with-qualification :do-not-publish})})

(defn valid-dimension-status?
  "True when status is valid for the given dimension."
  [dimension status]
  (contains? (get dimension-statuses dimension #{}) status))

(defn absent?
  "True when the status indicates non-assessment."
  [status]
  (contains? absent-statuses status))

(defn build-position
  "Build a researcher position map with model-component-level assessment.
   
   benchmark/content-root  — the benchmark content root that was evaluated
   researcher/id           — identifying the researcher
   outcome-hash            — the outcome hash from the researcher's run report
   dimensions              — map of dimension keyword to:
                             {:status keyword
                              :targets (optional) [{:kind keyword :id keyword
                                                     :component-hash sha256}]
                              :rationale string
                              :qualifications [string]}
   
   Returns the position map."
  [{:keys [benchmark/content-root researcher/id outcome-hash dimensions]}]
  (let [known-dimensions (set (keys dimension-statuses))
        provided (set (keys dimensions))
        extra (clojure.set/difference provided known-dimensions)]
    (when (seq extra)
      (throw (ex-info "Unknown position dimensions"
                      {:unknown extra :known known-dimensions})))
    (let [normalised-dims
          (reduce-kv
           (fn [m dim {:keys [status targets rationale qualifications]}]
             (if (valid-dimension-status? dim status)
               (assoc m dim
                      (cond-> {:status status}
                        (seq targets) (assoc :targets
                                             (mapv (fn [t]
                                                     {:kind (:kind t)
                                                      :id (:id t)
                                                      :component-hash (:component-hash t)})
                                                   targets))
                        rationale (assoc :rationale rationale)
                        (seq qualifications) (assoc :qualifications (vec qualifications))))
               (assoc m dim {:status :not-reviewed
                             :rationale (str "invalid status for dimension: " status)})))
           {}
           dimensions)
          base {:schema-version schema-version
                :benchmark/content-root content-root
                :researcher/id id
                :position/outcome-hash outcome-hash
                :position/dimensions normalised-dims}
          position-hash (hc/domain-hash :researcher-position base)]
      (assoc base :position/hash (str "sha256:" position-hash)))))

(defn position-hash
  "Return the content-addressed hash of the position."
  [position]
  (:position/hash position))

(defn dimension-status
  "Return the status keyword for a given dimension."
  [position dimension]
  (get-in position [:position/dimensions dimension :status]))

(defn dimension-targets
  "Return the component targets for a given dimension, or empty vector."
  [position dimension]
  (get-in position [:position/dimensions dimension :targets] []))

(defn position-valid?
  "Quick structural validity check for a researcher position."
  [position]
  (and (= schema-version (:schema-version position))
       (some? (:benchmark/content-root position))
       (some? (:researcher/id position))
       (some? (:position/hash position))
       (some? (:position/outcome-hash position))))
