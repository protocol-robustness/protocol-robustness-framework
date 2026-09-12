(ns resolver-sim.sim.fixtures-expected-outcome-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.io.fixtures :as fixtures]))

(deftest mixed-trace-entry-shapes-are-supported
  (let [result (fixtures/run-suite-from-key :suites/equivalence-escalation-boundaries nil nil nil)
        by-id  (into {} (map (juxt :trace-id identity) (:results result)))
        s48    (get by-id "s48-max-escalation-exact-boundary")
        s49    (get by-id "s49-max-escalation-plus-one-rejected")
        s50    (get by-id "s50-multi-hop-pending-cleared-every-hop")]
    (is (:ok? result) "Suite should pass with mixed keyword and map trace entries")
    (is (= :pass (:outcome s48)))
    (is (= :pass (:outcome s49))
        "Plus-one escalation at max level is rejected; scenario still completes")
    (is (= :pass (:outcome s50)))))
