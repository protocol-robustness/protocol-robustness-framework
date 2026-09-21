(ns resolver-sim.benchmark.research-analysis-comparison
  "Rooted comparison of independent analyses without claim promotion."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "research-analysis-comparison.v1")
(def ^:const policy :exact-coordinate-comparison.v1)

(defn- root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn comparison-root [comparison]
  (hash-ref/sha256-ref
   (hc/hash-with-intent {:hash/intent :research-analysis-comparison-v1}
                        (dissoc comparison :research-analysis-comparison/root))))

(defn- scope [analysis]
  (select-keys analysis [:research-definition/root :subject-roots :case-axis/root
                         :measure-axis/root :researcher-axis/root]))

(defn- validate-analysis! [analysis]
  (when-not (and (map? analysis)
                 (root? (:research-analysis/root analysis))
                 (map? (:analysis/coordinates analysis)))
    (throw (ex-info "Invalid rooted research analysis" {:analysis analysis})))
  analysis)

(defn compare-analyses
  "Compare two rooted coordinate maps.

  Missing keys and explicit `:missing` remain missing. Explicit
  `:not-comparable` propagates. Numeric zero is an ordinary value."
  [left right]
  (validate-analysis! left)
  (validate-analysis! right)
  (when-not (= (scope left) (scope right))
    (throw (ex-info "Research analysis scopes are incompatible"
                    {:left-scope (scope left) :right-scope (scope right)})))
  (let [left-values (:analysis/coordinates left)
        right-values (:analysis/coordinates right)
        coordinates (sort-by pr-str (set/union (set (keys left-values))
                                               (set (keys right-values))))
        output (mapv (fn [coordinate]
                       (let [left-present? (contains? left-values coordinate)
                             right-present? (contains? right-values coordinate)
                             left-value (get left-values coordinate)
                             right-value (get right-values coordinate)
                             status (cond
                                      (or (not left-present?) (not right-present?)
                                          (= :missing left-value) (= :missing right-value)) :missing
                                      (or (= :not-comparable left-value)
                                          (= :not-comparable right-value)) :not-comparable
                                      (= left-value right-value) :equal
                                      :else :different)]
                         {:coordinate coordinate
                          :left/value (if left-present? left-value :missing)
                          :right/value (if right-present? right-value :missing)
                          :comparison/status status}))
                     coordinates)
        comparison (merge {:artifact/schema schema
                           :comparison/policy policy
                           :comparison/inputs
                           [{:research-analysis/root (:research-analysis/root left)
                             :researcher (:researcher left)}
                            {:research-analysis/root (:research-analysis/root right)
                             :researcher (:researcher right)}]
                           :comparison/output output
                           :proof-of-possession/lineage
                           [(:proof-of-possession left) (:proof-of-possession right)]}
                          (scope left))]
    (assoc comparison :research-analysis-comparison/root (comparison-root comparison))))
