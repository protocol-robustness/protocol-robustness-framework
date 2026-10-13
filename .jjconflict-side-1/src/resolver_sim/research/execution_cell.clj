(ns resolver-sim.research.execution-cell
  "Rooted semantic identity for one independently executable research cell."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "research-execution-cell.v1")
(def ^:const canonical-root-predicate :canonical-root-equality.v1)
(def ^:private required #{:research-definition/root :policy-subject/root
                          :case-axis/root :measure-axis/root :case/id
                          :case/parameters :workload/root :execution/repeat-id
                          :execution/fixture-id :commit/predicate})
(def ^:private optional #{:execution/seed :distributed-experiment-profile/root})
(def ^:private fields (into #{:artifact/schema :research-execution-cell/root} (concat required optional)))

(defn semantic-projection [cell]
  (select-keys cell (disj fields :research-execution-cell/root)))

(defn cell-root [cell]
  (hash-ref/sha256-ref (hc/domain-hash :research-execution-cell-v1 (semantic-projection cell))))

(defn- root? [x] (hash-ref/valid-sha256-ref? x))
(defn- scoped-keyword? [x] (and (keyword? x) (namespace x)))

(defn validate-cell [cell]
  (let [keys* (when (map? cell) (set (keys cell)))
        missing (when keys* (seq (remove keys* (conj required :artifact/schema :research-execution-cell/root))))
        extra (when keys* (seq (remove fields keys*)))
        roots (cond-> [:research-definition/root :policy-subject/root :case-axis/root
                       :measure-axis/root :workload/root]
                (contains? cell :distributed-experiment-profile/root)
                (conj :distributed-experiment-profile/root))
        errors (cond-> []
                 (not (map? cell)) (conj :research-execution-cell/not-a-map)
                 (and (map? cell) (not= schema (:artifact/schema cell))) (conj :research-execution-cell/unsupported-schema)
                 (seq missing) (conj :research-execution-cell/missing-fields)
                 (seq extra) (conj :research-execution-cell/extra-fields)
                 (and (map? cell) (not (every? #(root? (get cell %)) roots))) (conj :research-execution-cell/invalid-root-reference)
                 (and (map? cell) (not (scoped-keyword? (:case/id cell)))) (conj :research-execution-cell/invalid-case-id)
                 (and (map? cell) (not (and (map? (:case/parameters cell))
                                            (every? (fn [[k v]] (and (scoped-keyword? k) (integer? v))) (:case/parameters cell)))))
                 (conj :research-execution-cell/invalid-case-parameters)
                 (and (map? cell) (not (and (integer? (:execution/repeat-id cell)) (not (neg? (:execution/repeat-id cell))))))
                 (conj :research-execution-cell/invalid-repeat-id)
                 (and (map? cell) (not (scoped-keyword? (:execution/fixture-id cell)))) (conj :research-execution-cell/invalid-fixture-id)
                 (and (map? cell) (contains? cell :execution/seed) (not (integer? (:execution/seed cell)))) (conj :research-execution-cell/invalid-seed)
                 (and (map? cell) (not (and (= canonical-root-predicate (get-in cell [:commit/predicate :predicate/kind]))
                                            (root? (get-in cell [:commit/predicate :expected/root]))
                                            (= #{:predicate/kind :expected/root} (set (keys (:commit/predicate cell)))))))
                 (conj :research-execution-cell/invalid-commit-predicate)
                 (and (map? cell) (not= (:research-execution-cell/root cell) (try (cell-root cell) (catch Exception _ nil))))
                 (conj :research-execution-cell/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)
     :missing-fields (vec (sort-by str missing)) :extra-fields (vec (sort-by str extra))}))

(defn valid-cell? [cell] (:valid? (validate-cell cell)))

(defn build-cell [input]
  (when-not (and (map? input) (= (set (keys input)) (into required (filter #(contains? input %) optional))))
    (throw (ex-info "Execution cell input contains unsupported fields" {:input input})))
  (let [base (assoc input :artifact/schema schema)
        rooted (assoc base :research-execution-cell/root (cell-root base))]
    (when-not (valid-cell? rooted)
      (throw (ex-info "Invalid research execution cell" (validate-cell rooted))))
    rooted))
