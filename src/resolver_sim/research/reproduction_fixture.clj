(ns resolver-sim.research.reproduction-fixture
  "A deliberately small reference computation for qualifying fresh
   reproduction evidence. It is not a new research-analysis authority path.

   The computation is an integer affine reduction: sum(input[i] * weight[i]).
   Its result classification is derived from a declared threshold. The expected
   result is intentionally absent from both input and execution artifacts and
   enters only at comparison time."
  (:require [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-assurance :as assurance]))

(def ^:const input-schema "reproduction-input.v1")
(def ^:const execution-schema "reproduction-execution.v1")
(def ^:const evidence-schema "reproduction-evidence.v1")

(defn- root [domain body]
  (hash-ref/sha256-ref (hc/domain-hash domain body)))

(defn input-root [input]
  (root :reproduction-input-v1 (dissoc input :reproduction-input/root)))

(defn build-input
  "Build canonical input for the qualification computation. It contains no
   expected/original result or execution/runner metadata."
  [{:keys [result-descriptor-root case-root values weights threshold]}]
  (when-not (and (hash-ref/valid-sha256-ref? result-descriptor-root)
                 (hash-ref/valid-sha256-ref? case-root)
                 (vector? values) (vector? weights)
                 (= (count values) (count weights)) (seq values)
                 (every? integer? values) (every? integer? weights)
                 (integer? threshold))
    (throw (ex-info "Invalid reproduction qualification input" {})))
  (let [body {:artifact/schema input-schema
              :computation/kind :qualification/integer-affine-threshold-v1
              :result-descriptor/root result-descriptor-root
              :result/case-root case-root
              :input/values values :input/weights weights
              :input/threshold threshold}]
    (assoc body :reproduction-input/root (input-root body))))

(defn valid-input? [input]
  (and (= input-schema (:artifact/schema input))
       (= :qualification/integer-affine-threshold-v1 (:computation/kind input))
       (= (:reproduction-input/root input)
          (try (input-root input) (catch Exception _ nil)))
       (try (= input (build-input
                      {:result-descriptor-root (:result-descriptor/root input)
                       :case-root (:result/case-root input)
                       :values (:input/values input) :weights (:input/weights input)
                       :threshold (:input/threshold input)}))
            (catch Exception _ false))))

(defn- computed-result [input]
  (let [value (reduce + (map * (:input/values input) (:input/weights input)))
        counterexample? (> value (:input/threshold input))
        base {:schema-version analysis/result-schema-version
              :result/descriptor-root (:result-descriptor/root input)
              :result/case-root (:result/case-root input)
              :result/value value
              :result/classification (if counterexample?
                                       :counterexample-found :supported)}
        result (cond-> base counterexample?
                 (assoc :result/witness-root
                        (root :reproduction-qualification-witness-v1
                              {:reproduction-input/root (:reproduction-input/root input)
                               :computed/value value
                               :threshold (:input/threshold input)})))]
    (assoc result :result/root (analysis/result-root result))))

(defn execution-root [execution]
  (root :reproduction-execution-v1
        (dissoc execution :reproduction-execution/root)))

(defn execute
  "Freshly run the fixed qualification computation. The API deliberately has
   no expected/original-result parameter. The optional thunk is test-only
   fault injection and receives only canonical input."
  ([input implementation] (execute input implementation computed-result))
  ([input implementation compute]
   (when-not (and (valid-input? input) (assurance/valid-implementation? implementation)
                  (fn? compute))
     (throw (ex-info "Invalid reproduction execution inputs" {})))
   (let [base {:artifact/schema execution-schema
               :reproduction-input/root (:reproduction-input/root input)
               :research-implementation-identity/root
               (:research-implementation-identity/root implementation)}]
     (try
       (let [result (compute input)]
         (when-not (and (:valid? (analysis/validate-result result))
                        (= (:result-descriptor/root input) (:result/descriptor-root result))
                        (= (:result/case-root input) (:result/case-root result)))
           (throw (ex-info "Fresh computation returned incompatible result" {})))
         (let [body (assoc base :execution/outcome :completed
                           :execution/fresh-result result)]
           (assoc body :reproduction-execution/root (execution-root body))))
       (catch Exception e
         (let [body (assoc base :execution/outcome :failed
                           :execution/failure-kind :computation-failed
                           :execution/failure-class (.getName (class e)))]
           (assoc body :reproduction-execution/root (execution-root body))))))))

(defn valid-execution? [execution]
  (and (= execution-schema (:artifact/schema execution))
       (hash-ref/valid-sha256-ref? (:reproduction-input/root execution))
       (hash-ref/valid-sha256-ref?
        (:research-implementation-identity/root execution))
       (contains? #{:completed :failed} (:execution/outcome execution))
       (if (= :completed (:execution/outcome execution))
         (and (= #{:artifact/schema :reproduction-input/root
                   :research-implementation-identity/root :execution/outcome
                   :execution/fresh-result :reproduction-execution/root}
                 (set (keys execution)))
              (:valid? (analysis/validate-result (:execution/fresh-result execution))))
         (and (= #{:artifact/schema :reproduction-input/root
                   :research-implementation-identity/root :execution/outcome
                   :execution/failure-kind :execution/failure-class
                   :reproduction-execution/root}
                 (set (keys execution)))
              (= :computation-failed (:execution/failure-kind execution))
              (string? (:execution/failure-class execution))))
       (= (:reproduction-execution/root execution)
          (try (execution-root execution) (catch Exception _ nil)))))

(defn evidence-root [evidence]
  (root :reproduction-evidence-v1 (dissoc evidence :reproduction-evidence/root)))

(defn compare-execution
  "Compare an already completed fresh execution with an authoritative recorded
   result. This is the first point at which that result is accepted as input."
  [execution authoritative-result]
  (when-not (and (valid-execution? execution) (map? authoritative-result))
    (throw (ex-info "Missing or invalid reproduction comparison input" {})))
  (let [authoritative-valid? (:valid? (analysis/validate-result authoritative-result))
        fresh (:execution/fresh-result execution)
        comparable? (and authoritative-valid? (= :completed (:execution/outcome execution))
                         (= (:result/descriptor-root fresh)
                            (:result/descriptor-root authoritative-result))
                         (= (:result/case-root fresh)
                            (:result/case-root authoritative-result)))
        status (cond
                 (= :failed (:execution/outcome execution)) :failed
                 (not comparable?) :not-comparable
                 (= (analysis/result-root fresh) (analysis/result-root authoritative-result))
                 :reproduced
                 :else :counterexample-found)
        body (cond-> {:artifact/schema evidence-schema
                       :reproduction-execution/root (:reproduction-execution/root execution)
                       :authoritative-result/root (when authoritative-valid?
                                                     (analysis/result-root authoritative-result))
                       :reproduction/status status}
               (= :completed (:execution/outcome execution))
               (assoc :reproduction/fresh-result-root (analysis/result-root fresh))
               (= :counterexample-found status)
               (assoc :reproduction/counterexample-result fresh
                      :reproduction/counterexample-witness-root
                      (or (:result/witness-root fresh)
                          (root :reproduction-disagreement-witness-v1
                                {:fresh-result/root (analysis/result-root fresh)
                                 :authoritative-result/root
                                 (analysis/result-root authoritative-result)}))))]
    (assoc body :reproduction-evidence/root (evidence-root body))))

(defn valid-evidence? [evidence]
  (and (= evidence-schema (:artifact/schema evidence))
       (hash-ref/valid-sha256-ref? (:reproduction-execution/root evidence))
       (contains? #{:reproduced :counterexample-found :failed :not-comparable}
                  (:reproduction/status evidence))
       (= (:reproduction-evidence/root evidence)
          (try (evidence-root evidence) (catch Exception _ nil)))))
