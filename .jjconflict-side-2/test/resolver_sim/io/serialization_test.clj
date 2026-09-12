(ns resolver-sim.io.serialization-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.io.serialization :as ser]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.normalize :as normalize]))

(deftest serialize-replay-result-with-yield-modules
  (testing "S19 replay result serializes (yield module ops are fns)"
    (let [raw      (io-sc/load-scenario-file
                    "scenarios/edn/S19_dr3-kleros-escalation-rejected-l0-resolves.edn")
          scenario (normalize/normalize-scenario raw)
          result   (sew/replay-with-sew-protocol scenario)
          json-str (ser/serialize-artifact result {:pretty? true})
          parsed   (json/read-str json-str :key-fn keyword)]
      (is (string? json-str))
      (is (= "pass" (:outcome parsed)))
      (is (pos? (count (:trace parsed)))))))
