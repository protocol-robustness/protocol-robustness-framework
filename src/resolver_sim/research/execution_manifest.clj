(ns resolver-sim.research.execution-manifest
  "Frozen Cartesian work sets for rooted execution cells."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-cell :as cell]))

(def ^:const schema "research-execution-manifest.v1")
(defn manifest-root [m]
  (hash-ref/sha256-ref (hc/domain-hash :research-execution-manifest-v1
                                       (dissoc m :research-execution-manifest/root))))
(defn- root? [x] (hash-ref/valid-sha256-ref? x))

(defn- expected-inputs [m]
  (for [policy (:dispatch/policy-subject-roots m)
        research-case (:dispatch/cases m)
        repeat (:dispatch/repeats m)
        :let [binding (some #(when (= (:case/id research-case) (:case/id %)) %)
                            (:dispatch/case-workloads m))]]
    (merge {:research-definition/root (:research-definition/root m)
            :policy-subject/root policy :case-axis/root (:case-axis/root m)
            :measure-axis/root (:measure-axis/root m)
            :case/id (:case/id research-case) :case/parameters (:case/parameters research-case)
            :workload/root (:workload/root binding)
            :commit/predicate {:predicate/kind cell/canonical-root-predicate
                               :expected/root (:expected/root binding)}}
           (select-keys binding [:distributed-experiment-profile/root])
           repeat)))

(defn validate-manifest [m]
  (let [cells (:dispatch/cells m)
        expected (try (->> (expected-inputs m) (map cell/build-cell)
                           (map :research-execution-cell/root) sort vec)
                      (catch Exception _ nil))
        actual (when (vector? cells) (->> cells (map :research-execution-cell/root) sort vec))
        errors (cond-> []
                 (not (map? m)) (conj :research-execution-manifest/not-a-map)
                 (and (map? m) (not= schema (:artifact/schema m))) (conj :research-execution-manifest/unsupported-schema)
                 (and (map? m) (not (every? #(root? (get m %)) [:research-definition/root :case-axis/root :measure-axis/root])))
                 (conj :research-execution-manifest/invalid-scope-root)
                 (not (and (vector? (:dispatch/policy-subject-roots m)) (seq (:dispatch/policy-subject-roots m))
                           (= (:dispatch/policy-subject-roots m) (vec (sort (:dispatch/policy-subject-roots m))))
                           (every? root? (:dispatch/policy-subject-roots m))))
                 (conj :research-execution-manifest/invalid-policy-subjects)
                 (not (and (vector? (:dispatch/repeats m)) (seq (:dispatch/repeats m))))
                 (conj :research-execution-manifest/invalid-repeats)
                 (not (and (vector? cells) (every? cell/valid-cell? cells) (= expected actual)))
                 (conj :research-execution-manifest/cell-set-mismatch)
                 (and (map? m) (not= (:research-execution-manifest/root m) (try (manifest-root m) (catch Exception _ nil))))
                 (conj :research-execution-manifest/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-manifest [frozen input]
  (when-not (and (map? input) (= #{:policy-subject-roots :case-workloads :repeats} (set (keys input))))
    (throw (ex-info "Execution manifest input contains unsupported fields" {:input input})))
  (let [cases (get-in frozen [:research-definition :research/cases])
        ids (set (map :case/id cases))
        workloads (:case-workloads input)]
    (when-not (and (= ids (set (keys workloads))) (seq (:policy-subject-roots input)) (seq (:repeats input)))
      (throw (ex-info "Manifest inputs must cover every frozen case and be nonempty" {})))
    (let [base {:artifact/schema schema
                :research-definition/root (:research-definition/root frozen)
                :case-axis/root (get-in frozen [:derived :case-axis/root])
                :measure-axis/root (get-in frozen [:derived :measure-axis/root])
                :dispatch/policy-subject-roots (vec (sort (:policy-subject-roots input)))
                :dispatch/cases (vec cases)
                :dispatch/case-workloads (mapv (fn [c] (merge {:case/id (:case/id c)} (workloads (:case/id c)))) cases)
                :dispatch/repeats (vec (sort-by (juxt :execution/repeat-id :execution/fixture-id) (:repeats input)))}
          cells (->> (expected-inputs base) (map cell/build-cell) (sort-by :research-execution-cell/root) vec)
          rooted (assoc base :dispatch/cells cells)
          rooted (assoc rooted :research-execution-manifest/root (manifest-root rooted))]
      (when-not (:valid? (validate-manifest rooted))
        (throw (ex-info "Invalid research execution manifest" (validate-manifest rooted))))
      rooted)))
