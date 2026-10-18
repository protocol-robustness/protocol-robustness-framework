(ns resolver-sim.protocols.sew.dispute-context.disagree-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.protocols.sew.dispute-context.disagree :as disagree]
            [resolver-sim.use-cases.registry :as use-cases]))

(deftest dispute-context-disagree-fixtures-are-case-bound
  (let [fixture (disagree/load-fixtures)
        validation (disagree/validate-fixtures fixture)
        [upheld overturned procedural unauthorized invalid dissent] (:cases fixture)]
    (is (:valid? validation) (pr-str (:errors validation)))
    (is (every? #(.isFile (io/file %)) (:fixture/source-scenarios fixture)))
    (is (= :upheld-on-merits (get-in upheld [:appeal :decision])))
    (is (= [:prior-resolver-per-policy 95]
           ((juxt :disposition :amount) (:bond upheld))))
    (is (= :overturned-on-merits (get-in overturned [:appeal :decision])))
    (is (= [:appellant-refund-per-policy 95]
           ((juxt :disposition :amount) (:bond overturned))))
    (is (= :procedurally-rejected (get-in procedural [:appeal :status])))
    (is (nil? (get-in procedural [:appeal :decision])))
    (is (= :unauthorized (get-in unauthorized [:appeal :reason])))
    (is (= :invalid (get-in invalid [:appeal :reason])))
    (is (= (get-in dissent [:settlement :before-root])
           (get-in dissent [:settlement :after-root])))
    (is (false? (:valid? (disagree/validate-case-binding
                          upheld (assoc upheld :case/id (:case/id overturned))))))
    (is (false? (:valid? (disagree/validate-fixtures
                          (assoc-in fixture [:cases 0 :bond]
                                    (:bond overturned))))))
    (is (false? (:valid? (disagree/validate-fixtures
                          (assoc-in fixture [:cases 5 :research :target/root]
                                    "another-basis")))))))

(deftest dispute-appeal-use-case-description-loads
  (let [registry (use-cases/load-use-case-registry
                  "examples/use-cases/ecommerce/registry.edn")
        appeal (some #(when (= :dispute/appeal-escalation (:concept/id %)) %)
                     (:use-cases registry))
        summary (:concept/summary appeal)]
    (is (some? appeal))
    (is (re-find #"procedural" summary))
    (is (re-find #"researcher disagreement" summary))))
