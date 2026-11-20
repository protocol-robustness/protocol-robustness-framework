(ns resolver-sim.definitions.runner-execution-registry-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.definitions.runner-execution-registry]))

(deftest runner-component-contributes-its-own-execution-modes
  (let [base-ids (set (map :id (:executions registries/execution-registry)))
        resolved (registries/resolved-execution-registry)
        resolved-ids (set (map :id (:executions resolved)))]
    (is (not (contains? base-ids :execution/simulation)))
    (is (contains? resolved-ids :execution/simulation))
    (is (contains? resolved-ids :execution/replay))
    (is (contains? resolved-ids :evidence/commitment-root))
    (is (:valid? (registries/validate-registry
                  :execution-registry resolved
                  {:entries-key :executions
                   :required-registry-fields #{:registry-version :executions}
                   :required-entry-fields #{:id :version :kind :runner :entry
                                            :execution/type :execution/mode
                                            :description :claims}
                   :registry-validator-fn
                   (fn [registry-name entries]
                     (registries/validate-execution-registry-entries
                      registry-name entries :entry-validation-mode :strict))})))))
