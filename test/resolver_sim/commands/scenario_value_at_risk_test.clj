(ns resolver-sim.commands.scenario-value-at-risk-test
  (:require [clojure.test :refer [deftest is]] [resolver-sim.commands.scenario-value-at-risk :as v]))
(def scenario {:value-at-risk {:at {:timestamp "2026-04-18T10:15:00Z" :event-index 8 :event-id "settlement-deadline-reached" :phase :post-event} :risk {:id "late-interaction-custody"} :value {:asset "USDC" :amount-encoding "scenario-native-integer"} :scope {:kind :workflow :id 42} :calculation {:selector [:workflows 42 :custody :held]}}})
(def replay {:trace [{:seq 8 :time 1776507300 :params {:event-id "settlement-deadline-reached"} :world {:workflows {42 {:custody {:held 10000}}}}}]})
(def provenance {:input/snapshot-relative "inputs/scenarios/example.edn"})
(def source "scenarios/example/execution/replay-output.json")
(deftest strict-observation
  (let [o (v/build-observation scenario replay provenance source)]
    (is (= "pass" (get o "status")))
    (is (true? (v/valid-amount? 10000 "scenario-native-integer")))
    (is (false? (v/valid-amount? -1 "scenario-native-integer")))
    (is (false? (v/valid-amount? 1.5 "scenario-native-integer")))
    (is (= "pass" (get (v/validate-persisted o scenario replay provenance source) "status")))
    (is (= "fail" (get (v/build-observation (assoc-in scenario [:value-at-risk :calculation :selector] [:workflows 99 :custody :held]) replay provenance source) "status")))
    (is (some #{"valid-amount"} (get-in o ["validation" "checks"]))))
  )
