(ns resolver-sim.benchmark.coverage
  "Derived benchmark coverage and concept maturity.

   Concept text is not evidence. This namespace derives the strongest maturity
   supported by scenario mappings, registered claims, runnable evaluators, and
   active benchmark manifests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.claims :as claims]
            [resolver-sim.config.paths :as paths]))

(def maturity-order
  [:defined :mapped :claimed :evaluated :benchmarked])

(def mechanical-claim-ids
  "Claims that verify pipeline presence rather than a protocol property."
  #{:evidence-root-present :replay-result-present :scenario-hash-present
    :no-invariant-errors :all-scenarios-pass})

(defn claim-ids
  "Normalize manifest claim references to their keyword IDs."
  [manifest]
  (mapv :claim/id (claims/normalize-claim-refs (:benchmark/claims manifest))))

(defn active-benchmark-errors
  "Return lifecycle errors for an active benchmark manifest.

   `known-claim-ids` comes from the declarative claim registry, while evaluator
   availability comes from the runnable evaluator dispatch table."
  [manifest known-claim-ids]
  (let [declared (claim-ids manifest)
        required (or (:benchmark/required-claims manifest) declared)
        deferred (or (:benchmark/deferred-scenario-claims manifest) #{})
        unknown (remove known-claim-ids required)
        unrunnable (remove claims/evaluator-resolver required)
        substantive (remove mechanical-claim-ids required)
        property-claims (or (:benchmark/property-claims manifest) {})
        unmapped (remove #(seq (get property-claims %))
                         (:benchmark/property-types manifest))]
    (cond-> []
      (seq (filter deferred required)) (conj :active/deferred-claims)
      (seq unknown) (conj :active/unknown-required-claims)
      (seq unrunnable) (conj :active/unrunnable-required-claims)
      (empty? substantive) (conj :active/mechanical-only)
      (seq unmapped) (conj :active/unmapped-advertised-property))))

(defn required-claims-passed?
  "True only when every required claim produced a passing result.

   This prevents an active benchmark from reporting success merely because its
   scenarios completed while a required evaluator was not exercised."
  [manifest claim-results]
  (let [required (set (or (:benchmark/required-claims manifest)
                          (claim-ids manifest)))
        outcomes-by-id (group-by :claim/id claim-results)]
    (every? (fn [claim-id]
              (let [outcomes (get outcomes-by-id claim-id)]
                (and (seq outcomes)
                     (every? #(= :pass (:claim/outcome %)) outcomes))))
            required)))

(defn pack-capability-errors
  "Validate explicit pack capabilities against child benchmark manifests.

   Demonstrated capabilities require at least one named active child benchmark
   with runnable required claims. Other statuses are explicit non-coverage and
   remain visible without being counted as demonstrated capability."
  [pack manifests-by-id known-claim-ids]
  (mapcat (fn [capability]
            (let [status (:capability/status capability)
                  benchmark-ids (:capability/benchmarks capability)
                  manifests (map manifests-by-id benchmark-ids)
                  supported? (some (fn [manifest]
                                     (and (= :active (:benchmark/status manifest))
                                          (empty? (active-benchmark-errors
                                                   manifest
                                                   known-claim-ids))))
                                   manifests)]
              (cond-> []
                (not (#{:demonstrated :partial :roadmap :research} status))
                (conj :pack/invalid-capability-status)

                (and (= :demonstrated status) (not supported?))
                (conj :pack/demonstrated-capability-unsupported))))
          (:pack/capabilities pack)))

(defn concept-coverage
  "Return derived coverage for one concept across benchmark manifests.

   A concept is only :benchmarked when an active benchmark includes it and all
   of that benchmark's required claims have runnable evaluators."
  [concept manifests]
  (let [concept-id (:concept/id concept)
        referenced (filter #(some #{concept-id} (:benchmark/concepts %)) manifests)
        scenario-mappings (vec (distinct (concat (get-in concept [:concept/maps-to :scenarios])
                                                 (map :scenario/id (mapcat :benchmark/scenarios referenced)))))
        mapped? (or (seq scenario-mappings)
                    (seq referenced))
        claims (->> (concat (get-in concept [:concept/maps-to :claims])
                            (mapcat claim-ids referenced))
                    distinct
                    vec)
        evaluated (filterv claims/evaluator-resolver claims)
        active (filter #(= :active (:benchmark/status %)) referenced)
        active-supported (filter (fn [manifest]
                                   (every? claims/evaluator-resolver
                                           (or (:benchmark/required-claims manifest)
                                               (claim-ids manifest))))
                                 active)
        maturity (cond
                   (seq active-supported) :benchmarked
                   (seq evaluated) :evaluated
                   (seq claims) :claimed
                   mapped? :mapped
                   :else :defined)]
    {:concept/id concept-id
     :concept/maturity maturity
     :concept/scenario-mapped? (boolean mapped?)
     :concept/scenario-mappings scenario-mappings
     :concept/claim-ids claims
     :concept/evaluator-claim-ids evaluated
     :concept/active-benchmark-ids (mapv :benchmark/id active-supported)
     :concept/known-gaps (cond-> []
                           (not mapped?) (conj :not-mapped-to-workload)
                           (empty? claims) (conj :no-registered-claims)
                           (and (seq claims) (empty? evaluated)) (conj :no-runnable-evaluator)
                           (empty? active-supported) (conj :not-in-active-benchmark))}))

(defn coverage-index
  "Return derived coverage records keyed by concept ID."
  [concepts manifests]
  (into {} (map (fn [concept]
                  [(:concept/id concept) (concept-coverage concept manifests)]))
        concepts))

(defn catalogue-manifests
  "Load all benchmark manifests with their pack-registry lifecycle status."
  ([] (catalogue-manifests (paths/benchmarks-registry)))
  ([registry-path]
   (let [registry (edn/read-string (slurp registry-path))]
     (vec
      (mapcat (fn [pack]
                (let [pack-path (io/file "benchmarks" (:pack/registry pack))
                      pack-registry (edn/read-string (slurp pack-path))
                      pack-dir (.getParent pack-path)]
                  (keep (fn [benchmark-ref]
                          (let [manifest-path (io/file pack-dir (:benchmark/file benchmark-ref))]
                            (when (.exists manifest-path)
                              (assoc (edn/read-string (slurp manifest-path))
                                     :benchmark/status (:benchmark/status benchmark-ref)
                                     :benchmark/pack-id (:pack/id pack-registry)))))
                        (:benchmarks pack-registry))))
              (:packs registry))))))

(defn catalogue-coverage
  "Derive concept maturity against every benchmark manifest in the catalogue."
  [concepts]
  (coverage-index concepts (catalogue-manifests)))
