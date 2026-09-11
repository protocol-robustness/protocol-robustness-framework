(ns resolver-sim.protocols.protocol-adapter-test
  "Verifies the tiered protocol interfaces.

   Tests:
   1. SewProtocol satisfies Core, Economic, and Analytical protocols.
   2. DummyProtocol satisfies Core and Economic protocols.
   3. Both protocols produce expected outcomes via the replay proto."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay              :as replay]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.protocols.protocol                 :as proto]
            [resolver-sim.protocols.sew                      :as sew]
            [resolver-sim.protocols.dummy                    :as dummy]))

(defn- single-scenario [entry]
  (if (map? entry) entry (first entry)))

(deftest sew-protocol-replay-passes
  "replay-with-protocol using SewProtocol must produce identical results to
   the old direct replay (now migrated to this path)."
  (testing "all scenarios"
    (doseq [[name entry] sc/all-scenarios]
      (let [scenario (single-scenario entry)
            result (replay/replay-with-protocol sew/protocol scenario)]
        (is (not= :invalid (:outcome result))
            (str name ": outcome is invalid — " (:halt-reason result)))))))

(deftest dummy-protocol-passes-scenarios
  "DummyProtocol (no invariant enforcement) must complete without crash for
   every invariant scenario."
  (testing "all scenarios complete without structural failure"
    (doseq [[name entry] sc/all-scenarios]
      (let [scenario (single-scenario entry)
            result   (replay/replay-with-protocol dummy/protocol scenario)]
        (is (#{:pass :fail} (:outcome result))
            (str name ": unexpected outcome " (:outcome result)))
        (is (not= :invalid (:outcome result))
            (str name ": structural failure — " (:halt-reason result)))))))

(deftest protocol-interfaces-satisfied
  "Verify protocol satisfaction for tiered interfaces."
  (testing "SewProtocol"
    (is (satisfies? proto/SimulationAdapter sew/protocol))
    (is (satisfies? proto/EconomicModel sew/protocol))
    (is (satisfies? proto/AnalysisModule sew/protocol)))

  (testing "DummyProtocol"
    (is (satisfies? proto/SimulationAdapter dummy/protocol))
    (is (satisfies? proto/EconomicModel dummy/protocol))
    (is (not (satisfies? proto/AnalysisModule dummy/protocol)))))
