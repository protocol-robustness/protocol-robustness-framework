(ns resolver-sim.commands.benchmark-validate-jar-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.benchmark-validate-jar :as validator]))

(deftest bare-bundled-scenario-path-is-forced-to-classpath-resolution
  (testing "A checkout-local scenario file cannot mask a missing JAR resource"
    (is (= "classpath:scenarios/edn/S-DR-001-basic-release-ruling.edn"
           (#'validator/classpath-ref "scenarios/edn/S-DR-001-basic-release-ruling.edn")))))

(deftest validation-rejects-legacy-discovery-and-filesystem-inputs
  (let [errors (atom [])
        plan (validator/->Plan :benchmark/legacy
                               "resource:benchmarks/legacy.edn"
                               {:input/type :classpath}
                               nil
                               1
                               [{:input/type :file :input/ref "file:/tmp/scenario.edn"}]
                               true)]
    (#'validator/validate-plan errors plan)
    (is (some #(re-find #":scenario-suites" %) @errors))
    (is (some #(re-find #"filesystem fallback" %) @errors))))

(deftest packaged-benchmark-corpus-has-classpath-only-plans
  (let [result (validator/validate-jar {})]
    (is (zero? (:exit-code result)) (:errors result))))
