(ns resolver-sim.run.comparison
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.paths :as paths]))
(defn- sha [f] (hash-ref/sha256-ref (lifecycle/sha256-file f)))
(defn- readj [f] (json/read-str (slurp f)))
(defn- verify-package! [root completion]
  (case (get completion "run_type")
    "scenario" ((requiring-resolve 'resolver-sim.scenario.verify/verify!) root)
    "benchmark" ((requiring-resolve 'resolver-sim.benchmark.verify/verify!) root)
    nil))

(defn submission [root]
  (try
    (let [root (io/file root) completion (io/file root paths/completion) c (readj completion)
          index (io/file root (get c "run_package_index_ref")) policy (io/file root "manifest/verdict-policy.json")
          i (readj index) p (readj policy)
          verification (verify-package! root c)]
      (cond
        (nil? verification) {:valid? false :reason :comparison/unsupported-package-profile}
        (not= "passed" (get verification "status")) {:valid? false :reason :comparison/package-verification-failed :verification verification}
        (not (and (= (get c "run_package_index_sha256") (sha index))
                  (= (get c "run_package_index_bytes") (.length index))))
        {:valid? false :reason :comparison/completion-invalid}
        (not (= (get p "policy_sha256") (hash-ref/sha256-ref (canonical/domain-hash :prf-verdict-policy-v1 (dissoc p "policy_sha256")))))
        {:valid? false :reason :comparison/verdict-policy-invalid}
        :else {:valid? true
               :submission {:schema-version "run-comparison-submission.v1"
                            :package {:completion-hash (sha completion) :package-index-hash (sha index)
                                      :run-id (get c "run_id") :scenario-id (get-in i ["scenario" "id"])}
                            :verdict {:policy-hash (get p "policy_sha256") :semantic-outcome (get-in p ["verdict" "semantic_outcome"])}
                            :evaluator {:identifier (get-in p ["evaluator_implementation" "evaluator_id"])
                                        :implementation-hash (get-in p ["evaluator_implementation" "source_tree_hash"])
                                        :hash-algorithm (get-in p ["evaluator_implementation" "source_tree_hash_algorithm"])}
                            :distribution (get p "distribution_provenance")}}))
    (catch Exception _ {:valid? false :reason :comparison/unreadable-package})))
(defn compare [left right]
  (cond (and (not (:valid? left)) (not (:valid? right))) {:classification :invalid-both-packages}
        (not (:valid? left)) {:classification :invalid-left-package}
        (not (:valid? right)) {:classification :invalid-right-package}
        :else (let [a (:submission left) b (:submission right)
                    cats {:semantic-outcome? (= (get-in a [:verdict :semantic-outcome]) (get-in b [:verdict :semantic-outcome]))
                          :verdict-policy? (= (get-in a [:verdict :policy-hash]) (get-in b [:verdict :policy-hash]))
                          :evaluator-implementation? (= (:evaluator a) (:evaluator b))
                          :distribution-integrity? (= (:distribution a) (:distribution b))}
                    agreed? (every? true? (vals cats))
                    failed? (= "fail" (get-in a [:verdict :semantic-outcome]))]
                {:schema-version "run-comparison.v1" :left-submission a :right-submission b
                 :classification (if agreed? (if failed? :semantic-failure-agreement :agreement) :unexpected-divergence)
                 :agreement cats :differences (vec (for [[k v] cats :when (not v)] k))
                 :result (if agreed? :pass :fail)})))
