(ns resolver-sim.benchmark.research-analysis-composition
  "Deterministic composition of rooted analyses without claim promotion."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "research-analysis-composition.v1")
(def operators #{:agreement-count :minimum :maximum})
(def missing-policies #{:propagate :ignore})
(def not-comparable-policies #{:propagate :exclude})

(defn composition-root [composition]
  (hash-ref/sha256-ref
   (hc/hash-with-intent {:hash/intent :research-analysis-composition-v1}
                        (dissoc composition :research-analysis-composition/root))))

(defn- scope [analysis]
  (select-keys analysis [:research-definition/root :subject-roots :case-axis/root
                         :measure-axis/root :researcher-axis/root]))

(defn- validate-policy! [{:keys [operator missing-data-policy not-comparable-policy
                                 integer-arithmetic-policy rounding-policy]}]
  (when-not (operators operator)
    (throw (ex-info "Unsupported composition operator" {:operator operator})))
  (when-not (missing-policies missing-data-policy)
    (throw (ex-info "Unsupported missing-data policy" {:policy missing-data-policy})))
  (when-not (not-comparable-policies not-comparable-policy)
    (throw (ex-info "Unsupported not-comparable policy" {:policy not-comparable-policy})))
  (when-not (= :exact-integer.v1 integer-arithmetic-policy)
    (throw (ex-info "Composition requires exact integer arithmetic" {})))
  (when-not (= :not-applicable rounding-policy)
    (throw (ex-info "Composition V1 does not round" {}))))

(defn- derive-value [operator missing-policy incomparable-policy values]
  (cond
    (and (= :propagate missing-policy) (some #{:missing} values)) :missing
    (and (= :propagate incomparable-policy) (some #{:not-comparable} values)) :not-comparable
    :else
    (let [eligible (->> values
                        (remove #{:missing :not-comparable})
                        vec)]
      (when-not (every? integer? eligible)
        (throw (ex-info "Eligible composition inputs must be canonical integers"
                        {:values values})))
      (if (empty? eligible)
        (if (some #{:not-comparable} values) :not-comparable :missing)
        (case operator
          :agreement-count (bigint (apply max (vals (frequencies eligible))))
          :minimum (apply min eligible)
          :maximum (apply max eligible))))))

(defn compose-analyses
  "Compose rooted coordinate analyses under a closed deterministic policy.

  Input order is canonicalized by analysis root. The output remains analytical
  evidence and explicitly cannot establish a general proof or accepted claim."
  [analyses policy]
  (validate-policy! policy)
  (let [analyses (vec (sort-by :research-analysis/root analyses))]
    (when (< (count analyses) 2)
      (throw (ex-info "Composition requires at least two analyses" {})))
    (when-not (every? #(and (hash-ref/valid-sha256-ref? (:research-analysis/root %))
                            (map? (:analysis/coordinates %))) analyses)
      (throw (ex-info "Composition input is not a rooted analysis" {})))
    (when-not (apply = (map scope analyses))
      (throw (ex-info "Research analysis scopes are incompatible" {})))
    (let [all-coordinates (sort-by pr-str
                                   (apply set/union #{}
                                          (map #(set (keys (:analysis/coordinates %))) analyses)))
          values-at (fn [coordinate]
                      (mapv #(get (:analysis/coordinates %) coordinate :missing) analyses))
          output (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [coordinate]
                              [coordinate
                               (derive-value (:operator policy)
                                             (:missing-data-policy policy)
                                             (:not-comparable-policy policy)
                                             (values-at coordinate))]))
                       all-coordinates)
          composition (merge {:artifact/schema schema
                              :composition/operator (:operator policy)
                              :composition/policy policy
                              :composition/input-analysis-roots
                              (mapv :research-analysis/root analyses)
                              :composition/output output
                              :proof-of-possession/lineage
                              (mapv :proof-of-possession analyses)
                              :research-analysis/general-ic-proven? false
                              :research-claim/accepted? false}
                             (scope (first analyses)))]
      (assoc composition :research-analysis-composition/root
             (composition-root composition)))))
