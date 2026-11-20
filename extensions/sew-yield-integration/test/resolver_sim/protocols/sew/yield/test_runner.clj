(ns resolver-sim.protocols.sew.yield.test-runner
  "Run the Sew yield integration's package-owned contract tests."
  (:require [clojure.test :as test]
            [resolver-sim.protocols.sew.yield.failure-test]
            [resolver-sim.protocols.sew.yield.finalize-parity-test]
            [resolver-sim.protocols.sew.yield.policy-test]
            [resolver-sim.protocols.sew.yield.risk-policy-test]))

(def test-namespaces
  '[resolver-sim.protocols.sew.yield.failure-test
    resolver-sim.protocols.sew.yield.finalize-parity-test
    resolver-sim.protocols.sew.yield.policy-test
    resolver-sim.protocols.sew.yield.risk-policy-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (System/exit (+ fail error))))
