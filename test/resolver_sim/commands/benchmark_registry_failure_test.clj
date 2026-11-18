(ns resolver-sim.commands.benchmark-registry-failure-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.commands.benchmark :as benchmark]
            [resolver-sim.concepts.registry :as registry]))

(deftest registry-load-failure-preserves-the-cause
  (let [errors (atom [])]
    (with-redefs [registry/load-registry
                  (fn [& _]
                    (throw (ex-info "concept registry validation failed"
                                    {:error :concepts/validation-failed
                                     :violations [{:missing [:concept/metrics]}]})))]
      (#'benchmark/validate-manifest errors
                                     {:benchmark/concepts [:assurance/example]
                                      :benchmark/scenarios [{:dimension :assurance/example}]}
                                     "broken-registry-example.edn" :benchmark/example :draft))
    (is (some #(re-find #"Global concept registry load failed" %) @errors))
    (is (some #(re-find #":concepts/validation-failed" %) @errors))
    (is (some #(re-find #":concept/metrics" %) @errors))
    (is (not-any? #(re-find #"Unknown (concept|scenario dimension)" %) @errors))))
