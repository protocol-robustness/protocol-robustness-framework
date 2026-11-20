(ns resolver-sim.protocols.sew.forking-strategist-expectations-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios.adversarial :as adversarial]
            [resolver-sim.scenario.expectations :as expectations]))

(defn- assert-scenario-passes [scenario]
  (let [result (sew/replay-with-sew-protocol scenario)
        exp    (when (:expectations scenario)
                 (expectations/evaluate-expectations result (:expectations scenario)))]
    (is (= :pass (:outcome result))
        (str (:scenario-id scenario) " replay: " (pr-str (select-keys result [:outcome :halt-reason]))))
    (when exp
      (is (true? (:ok? exp))
          (str (:scenario-id scenario) " expectations: " (pr-str (:violations exp)))))))

(deftest forking-strategist-scenarios-pass-with-declared-expectations
  (testing "S26–S33 forking-strategist family"
    (doseq [scenario [adversarial/s26 adversarial/s27 adversarial/s28 adversarial/s29
                      adversarial/s30 adversarial/s31 adversarial/s32 adversarial/s33]]
      (assert-scenario-passes scenario))))
