(ns resolver-sim.protocols.sew.capacity-exhaustion-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.sim.capacity-exhaustion :as simulation])
  (:import [java.io StringWriter]))

(deftest capacity-exhaustion-scenarios-preserve-the-capacity-invariant
  (let [result (atom nil)]
    (binding [*out* (StringWriter.)]
      (reset! result (simulation/run-all)))
    (is (= {:passed 6 :failed 0 :total 6} @result))))
