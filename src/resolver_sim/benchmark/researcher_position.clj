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

   Theorem/conclusion targeting:
     Researchers may submit positions against individual theorem and
     conclusion hashes:

     {:position/target
      {:kind :theorem
       :id :theorem/incentive-compatibility
       :hash \"sha256:...\"}
      :position/status :qualified
      :position/rationale \"...\"}

     This allows a researcher to reproduce one theorem while challenging
     another without disputing the entire outcome.

   The position is submitted after the researcher has inspected their
   own report, the other two reports, divergences and model coverage.
   It is NOT required to complete a run."
  (:require [clojure.set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "researcher-position.v1")

(def ^:const target-kinds
  "Controlled vocabulary for position target kinds.
   A position may target a theorem, conclusion, or model dimension."
  #{:theorem :conclusion :dimension})

(def ^:const target-statuses
  "Controlled vocabulary for theorem/conclusion target statuses.
   Unlike dimension-level statuses, these reflect researcher consensus
   on individual theorem or conclusion outcomes."
  #{:reproduced :unable-to-reproduce :qualified
    :challenged :supported :not-supported
    :not-evaluable
    :not-reviewed :insufficient-information :not-applicable})

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
   :model-invariants (into absent-statuses #{:adequate :incomplete :inadequate})
   :temporal-fidelity (into absent-statuses #{:adequate :incomplete :inadequate})
   :sampling-policy (into absent-statuses #{:adequate :insufficient-coverage :biased})
   :incentives-participants (into absent-statuses #{:adequate :incomplete})
   :incentives-strategies (into absent-statuses #{:adequate :incomplete :omitted})
   :incentives-coalitions (into absent-statuses #{:adequate :omitted})
   :evidence      (into absent-statuses #{:sufficient :insufficient})
   :claims        (into absent-statuses #{:supported :overstated :not-supported})
   :publication   (into absent-statuses #{:publish :publish-with-qualification :do-not-publish})
   :determinism   (into absent-statuses #{:deterministic :non-deterministic :incomplete})
   :provenance    (into absent-statuses #{:complete :incomplete :broken})})

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

   Optional:
   position/targets        — vector of theorem/conclusion targets:
                             [{:kind :theorem|:conclusion
                               :id keyword
                               :hash sha256
                               :status keyword
                               :rationale string}]

   Returns the position map."
  [{:keys [benchmark/content-root researcher/id outcome-hash dimensions position/targets]}]
  (let [known-dimensions (set (keys dimension-statuses))
        provided (set (keys dimensions))
        extra (clojure.set/difference provided known-dimensions)]
    (when (seq extra)
      (throw (ex-info "Unknown position dimensions"
                      {:unknown extra :known known-dimensions})))
    (let [normalised-dims
          (reduce-kv
           (fn [m dim {:keys [status targets rationale qualifications]}]
             (when-not (valid-dimension-status? dim status)
               (throw (ex-info (str "Invalid status for dimension " dim ": " status)
                               {:dimension dim :status status
                                :allowed (get dimension-statuses dim)})))
             (assoc m dim
                    (cond-> {:status status}
                      (seq targets) (assoc :targets
                                           (mapv (fn [t]
                                                   {:kind (:kind t)
                                                    :id (:id t)
                                                    :component-hash (:component-hash t)})
                                                 targets))
                      rationale (assoc :rationale rationale)
                      (seq qualifications) (assoc :qualifications (vec qualifications)))))
           {}
           dimensions)
          normalised-targets
          (when (seq targets)
            (mapv (fn [t]
                    (let [kind (:kind t)
                          id (:id t)]
                      (when-not (contains? target-kinds kind)
                        (throw (ex-info "Invalid target kind"
                                        {:kind kind :allowed target-kinds})))
                      (when-not (contains? target-statuses (:status t))
                        (throw (ex-info "Invalid target status"
                                        {:status (:status t)
                                         :allowed target-statuses})))
                      (when-not (keyword? id)
                        (throw (ex-info "Target :id must be a keyword"
                                        {:id id})))
                      (when-not (and (string? (:hash t))
                                     (re-matches #"sha256:.+" (:hash t)))
                        (throw (ex-info "Target :hash must be a sha256 content reference"
                                        {:hash (:hash t)})))
                      {:kind kind
                       :id id
                       :hash (:hash t)
                       :status (:status t)
                       :rationale (:rationale t)}))
                  targets))
          base (merge {:schema-version schema-version
                       :benchmark/content-root content-root
                       :researcher/id id
                       :position/outcome-hash outcome-hash
                       :position/dimensions normalised-dims}
                      (when normalised-targets
                        {:position/targets normalised-targets}))
          position-hash (hc/domain-hash :researcher-position base)]
      (assoc base :position/hash (hash-ref/sha256-ref  position-hash)))))

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
  "Validate the structural and content-hash integrity required for a position
   to be used as a certificate source."
  [position]
  (and (= schema-version (:schema-version position))
       (some? (:benchmark/content-root position))
       (some? (:researcher/id position))
       (some? (:position/outcome-hash position))
       (string? (:position/hash position))
       (= (:position/hash position)
          (hash-ref/sha256-ref (hc/domain-hash :researcher-position
                                               (dissoc position :position/hash))))
       (every? (fn [target]
                 (and (contains? target-kinds (:kind target))
                      (keyword? (:id target))
                      (string? (:hash target))
                      (re-matches #"sha256:.+" (:hash target))
                      (contains? target-statuses (:status target))))
               (:position/targets position []))))

(defn position-targets
  "Return the theorem/conclusion targets for a position, or empty vector."
  [position]
  (:position/targets position []))

(defn find-target
  "Find a specific target by :kind and :id within a position.
   Returns the target map or nil."
  [position kind target-id]
  (some #(when (and (= kind (:kind %)) (= target-id (:id %))) %)
        (position-targets position)))

(defn target-status
  "Return the status keyword for a specific target within a position.
   Returns nil when the target is not found."
  [position kind target-id]
  (:status (find-target position kind target-id)))
